package com.euver.kiwin.action

import com.euver.kiwin.application.ExpandStatementUseCase
import com.euver.kiwin.model.AssemblyResult
import com.euver.kiwin.model.StatementInfo
import com.euver.kiwin.service.ConsoleOutputService
import com.euver.kiwin.service.NotificationService
import com.euver.kiwin.service.MapperIndexService
import com.intellij.ide.util.PsiElementListCellRenderer
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtil
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.ide.highlighter.XmlFileType
import javax.swing.Icon

/**
 * MyBatis SQL 组装 Action
 * 支持在 XML Statement 或 Java Mapper 接口方法上右键触发,组装完整的 SQL 内容
 * 
 * 表示层职责：负责 UI 交互，委托应用层处理业务逻辑
 */
class AssembleSqlAction : AnAction() {

    private val logger = thisLogger()

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val psiFile = e.getData(CommonDataKeys.PSI_FILE)
        val editor = e.getData(CommonDataKeys.EDITOR)

        if (project == null || psiFile == null || editor == null) {
            e.presentation.isVisible = false
            e.presentation.isEnabled = false
            return
        }

        // 支持在 XML 文件或 Java 文件中显示菜单项
        when (psiFile) {
            is XmlFile -> {
                e.presentation.isVisible = true
                val element = psiFile.findElementAt(editor.caretModel.offset)
                
                // 优先检查是否在 SQL 片段 ID 上（<sql> 的 id 或 <include> 的 refid）
                val sqlFragmentId = findSqlFragmentIdAtCursor(element)
                if (sqlFragmentId != null) {
                    e.presentation.isEnabled = true
                    return
                }
                
                // 检查是否在 Statement 标签内
                val statementTag = findStatementTag(element)
                e.presentation.isEnabled = statementTag != null
            }
            is PsiJavaFile -> {
                e.presentation.isVisible = true
                val element = psiFile.findElementAt(editor.caretModel.offset)
                
                // 场景1：检查光标是否在 Mapper 接口的方法定义上
                val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
                if (method != null) {
                    val containingClass = method.containingClass
                    if (containingClass != null && containingClass.isInterface && 
                        hasMapperXmlInHierarchy(project, containingClass)) {
                        e.presentation.isEnabled = true
                        return
                    }
                }
                
                // 场景2：检查光标是否在方法调用表达式上（如 myDao.getInfoById()）
                val methodInfo = findMapperMethodFromCallExpressionWithQualifierType(project, element)
                e.presentation.isEnabled = methodInfo != null
            }
            else -> {
                e.presentation.isVisible = false
                e.presentation.isEnabled = false
            }
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return

        logger.info("触发 MyBatis SQL 组装功能")

        try {
            // 根据文件类型选择不同的处理逻辑
            when (psiFile) {
                is XmlFile -> handleXmlFileAction(project, psiFile, editor)
                is PsiJavaFile -> handleJavaFileAction(project, psiFile, editor)
                else -> {
                    val notificationService = NotificationService(project)
                    notificationService.showErrorNotification("不支持的文件类型")
                }
            }
        } catch (e: Exception) {
            logger.error("SQL 组装过程发生异常", e)
            val notificationService = NotificationService(project)
            notificationService.showErrorNotification("SQL 组装失败: ${e.message}")
        }
    }

    /**
     * 处理在 XML 文件中的操作
     */
    private fun handleXmlFileAction(project: Project, psiFile: XmlFile, editor: com.intellij.openapi.editor.Editor) {
        val element = psiFile.findElementAt(editor.caretModel.offset)
        val useCase = ExpandStatementUseCase(project)
        
        // 优先检查是否在 SQL 片段 ID 上（<sql> 的 id 或 <include> 的 refid）
        val sqlFragmentId = findSqlFragmentIdAtCursor(element)
        if (sqlFragmentId != null) {
            logger.info("检测到光标在 SQL 片段 ID 上: $sqlFragmentId")
            val result = useCase.executeFromSqlFragmentId(sqlFragmentId, psiFile)
            if (result == null) {
                NotificationService(project).showErrorNotification("未找到 SQL 片段: $sqlFragmentId")
                return
            }
            outputResult(project, result)
            return
        }
        
        // 查找光标所在的 Statement 标签
        val statementTag = findStatementTag(element)

        if (statementTag == null) {
            NotificationService(project).showErrorNotification("未找到对应的 MyBatis Statement 定义")
            return
        }

        // 使用应用层服务展开 Statement
        val result = useCase.executeFromTag(statementTag, psiFile)
        
        if (result == null) {
            NotificationService(project).showErrorNotification("无法解析 Statement 信息")
            return
        }

        // 输出结果
        outputResult(project, result)
    }

    /**
     * 处理在 Java 文件中的操作
     * 
     * 支持两种场景：
     * 1. 直接在 Mapper 接口方法定义上
     * 2. 在方法调用表达式上（如 myDao.getInfoById()）
     * 
     * 关键改进：
     * - 从 qualifier 类型获取正确的接口类型，解决继承场景下的 namespace 匹配问题
     * - 支持多 Mapper XML 候选时弹出选择框，让用户决定使用哪一份（对齐 IDEA 原生行为）
     */
    private fun handleJavaFileAction(project: Project, psiFile: PsiJavaFile, editor: com.intellij.openapi.editor.Editor) {
        val element = psiFile.findElementAt(editor.caretModel.offset)
        val useCase = ExpandStatementUseCase(project)
        
        // 场景1：直接在 Mapper 接口方法定义上
        val directMethod = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
        if (directMethod != null) {
            val containingClass = directMethod.containingClass
            if (containingClass != null && containingClass.isInterface && 
                hasMapperXmlInHierarchy(project, containingClass)) {
                logger.info("找到 Mapper 接口方法定义: ${containingClass.qualifiedName}.${directMethod.name}")
                
                handleStatementExecution(project, useCase, containingClass, directMethod.name, editor)
                return
            }
        }
        
        // 场景2：在方法调用表达式上（使用 qualifier 类型解决继承问题）
        val methodInfo = findMapperMethodFromCallExpressionWithQualifierType(project, element)
        if (methodInfo != null) {
            val (resolvedMethod, qualifierClass) = methodInfo
            logger.info("找到 Mapper 方法调用: ${qualifierClass.qualifiedName}.${resolvedMethod.name}")
            
            handleStatementExecution(project, useCase, qualifierClass, resolvedMethod.name, editor)
            return
        }
        
        NotificationService(project).showErrorNotification("未找到 Mapper 接口方法")
    }
    
    /**
     * 处理 Statement 执行逻辑，支持多候选时弹出选择框
     * 
     * 对齐 IDEA 原生导航行为：
     * - 0 个候选：提示"未找到 Statement"
     * - 1 个候选：直接使用，保持无感体验
     * - 多个候选：弹出选择框，让用户决定使用哪一份 Mapper XML
     * 
     * @param project 当前项目
     * @param useCase 用例服务
     * @param callerClass 调用者接口类型
     * @param methodName 方法名
     * @param editor 编辑器实例，用于定位弹出面板显示位置
     */
    private fun handleStatementExecution(
        project: Project,
        useCase: ExpandStatementUseCase,
        callerClass: PsiClass,
        methodName: String,
        editor: com.intellij.openapi.editor.Editor
    ) {
        // 获取所有候选 Statement
        val candidates = useCase.findCandidateStatementsWithInheritance(callerClass, methodName)
        
        when {
            candidates.isEmpty() -> {
                // 场景1：未找到任何候选
                val message = "未找到对应的 Statement: $methodName"
                NotificationService(project).showErrorNotification(message)
                ConsoleOutputService(project).outputErrorMessage("$message (Mapper: ${callerClass.qualifiedName})")
            }
            
            candidates.size == 1 -> {
                // 场景2：只有一个候选，直接使用（保持现有体验）
                val result = useCase.execute(candidates.first())
                outputResult(project, result)
            }
            
            else -> {
                // 场景3：有多个候选，弹出选择框让用户决定
                showStatementChooser(project, candidates, methodName, editor)
                // 注意：弹出面板是异步的，用户选择后会在 onChosen 回调中处理
            }
        }
    }
    
    /**
     * 显示 Statement 选择弹出面板
     * 
     * 完全对齐 IDEA 原生"Go To Implementation"的弹出面板样式：
     * - 使用 JBPopupFactory + BaseListPopupStep 创建列表弹出面板
     * - 自定义显示 XML 图标、Statement ID、文件名信息
     * - 支持鼠标悬停高亮和键盘导航
     * - 显示候选数量统计
     * 
     * @param project 当前项目
     * @param candidates 候选 Statement 列表
     * @param methodName 方法名
     * @param editor 编辑器实例，用于定位弹出面板显示位置
     * @return 用户选择的 StatementInfo，如果取消则返回 null
     */
    private fun showStatementChooser(
        project: Project,
        candidates: List<StatementInfo>,
        methodName: String,
        editor: com.intellij.openapi.editor.Editor
    ): StatementInfo? {
        // 创建弹出面板步骤，使用回调处理用户选择
        val popupStep = object : BaseListPopupStep<StatementInfo>(
            "Choose Implementation of $methodName (${candidates.size} found)",
            candidates
        ) {
            override fun getTextFor(value: StatementInfo): String {
                // 主显示文本：statementId (文件名)
                return "${value.statementId} (${value.sourceFile.name})"
            }
            
            override fun getIconFor(value: StatementInfo?): Icon? {
                // 使用 XML 文件类型图标
                return XmlFileType.INSTANCE.icon
            }
            
            override fun onChosen(selectedValue: StatementInfo?, finalChoice: Boolean): PopupStep<*>? {
                if (finalChoice && selectedValue != null) {
                    // 用户确认选择后，立即执行 SQL 组装
                    val useCase = ExpandStatementUseCase(project)
                    val result = useCase.execute(selectedValue)
                    outputResult(project, result)
                }
                return PopupStep.FINAL_CHOICE
            }
            
            override fun canceled() {
                // 用户取消选择
                logger.info("用户取消选择 Statement")
            }
        }
        
        // 创建并显示弹出面板
        val popup = JBPopupFactory.getInstance()
            .createListPopup(popupStep)
        
        // 在编辑器最佳位置显示
        popup.showInBestPositionFor(editor)
        
        // 由于弹出面板是异步的，这里返回 null，实际处理在 onChosen 回调中
        return null
    }
    
    /**
     * 从方法调用表达式中查找 Mapper 方法及其调用者接口类型
     * 
     * 关键改进：使用 qualifier 的类型作为目标接口，而不是方法定义所在的接口
     * 这解决了继承场景下的问题（方法定义在父接口，但 XML namespace 是子接口）
     * 
     * @return Pair<被调用的方法, 调用者接口类型>，用于解决继承场景下的 namespace 匹配问题
     */
    private fun findMapperMethodFromCallExpressionWithQualifierType(
        project: Project, 
        element: PsiElement?
    ): Pair<PsiMethod, PsiClass>? {
        if (element == null) return null
        
        val methodCallExpr = PsiTreeUtil.getParentOfType(element, PsiMethodCallExpression::class.java)
            ?: return null
        
        val resolvedMethod = methodCallExpr.resolveMethod() ?: return null
        
        // 获取 qualifier 类型（调用者变量的实际类型）
        // 例如：userMapper.getById() 中，qualifier 是 userMapper，其类型是 UserMapper
        val qualifier = methodCallExpr.methodExpression.qualifierExpression
        val qualifierType = qualifier?.type
        val qualifierClass = PsiUtil.resolveClassInClassTypeOnly(qualifierType)
        
        // 优先使用 qualifier 类型，如果获取不到则回退到方法定义所在的类
        val targetClass = qualifierClass ?: resolvedMethod.containingClass ?: return null
        
        // 验证是否为接口
        if (!targetClass.isInterface) return null
        
        // 验证该接口或其父接口是否有对应的 Mapper XML
        if (!hasMapperXmlInHierarchy(project, targetClass)) return null
        
        return Pair(resolvedMethod, targetClass)
    }
    
    /**
     * 检查接口或其父接口是否有对应的 Mapper XML
     * 递归遍历继承链查找
     */
    private fun hasMapperXmlInHierarchy(project: Project, psiClass: PsiClass): Boolean {
        val indexService = MapperIndexService.getInstance(project)
        
        // 检查当前接口
        val namespace = psiClass.qualifiedName
        if (namespace != null && indexService.findMapperFileByNamespace(namespace) != null) {
            return true
        }
        
        // 递归检查父接口
        for (superInterface in psiClass.interfaces) {
            if (hasMapperXmlInHierarchy(project, superInterface)) {
                return true
            }
        }
        
        return false
    }

    /**
     * 输出组装结果
     */
    private fun outputResult(project: Project, result: AssemblyResult) {
        // 显示通知
        NotificationService(project).showAssemblyResultNotification(result)

        // 输出到控制台（无论成功还是失败都显示）
        logger.info("准备输出到控制台窗口...")
        val outputService = ConsoleOutputService(project)
        outputService.outputToConsole(result)
        logger.info("控制台输出完成")

        // 如果存在循环引用，不复制到剪贴板
        if (result.hasCircularReference()) {
            logger.warn("存在循环引用，跳过剪贴板复制")
            return
        }

        // 复制到剪贴板
        val copySuccess = outputService.copyToClipboard(result.assembledSql)
        if (!copySuccess) {
            logger.warn("复制到剪贴板失败,但控制台输出成功")
        }
    }

    /**
     * 查找包含当前元素的 Statement 标签
     */
    private fun findStatementTag(element: PsiElement?): XmlTag? {
        if (element == null) return null

        // 向上查找父级标签
        var currentTag = PsiTreeUtil.getParentOfType(element, XmlTag::class.java)

        while (currentTag != null) {
            val tagName = currentTag.name
            if (tagName in setOf("select", "insert", "update", "delete")) {
                return currentTag
            }
            currentTag = PsiTreeUtil.getParentOfType(currentTag, XmlTag::class.java)
        }

        return null
    }
    
    /**
     * 查找光标所在位置的 SQL 片段 ID
     * 支持两种场景：
     * 1. 光标在 <sql id="xxx"> 的 id 属性值上
     * 2. 光标在 <include refid="xxx"/> 的 refid 属性值上
     * 
     * @return SQL 片段 ID，如果光标不在相关位置则返回 null
     */
    private fun findSqlFragmentIdAtCursor(element: PsiElement?): String? {
        if (element == null) return null
        
        // 检查是否在 XML 属性值内
        val attrValue = PsiTreeUtil.getParentOfType(element, XmlAttributeValue::class.java)
            ?: return null
        
        // 获取属性
        val attr = attrValue.parent as? com.intellij.psi.xml.XmlAttribute
            ?: return null
        
        // 获取所属标签
        val tag = attr.parent as? XmlTag
            ?: return null
        
        return when {
            // 场景1：<sql id="xxx"> 的 id 属性
            tag.name == "sql" && attr.name == "id" -> {
                attrValue.value
            }
            // 场景2：<include refid="xxx"/> 的 refid 属性
            tag.name == "include" && attr.name == "refid" -> {
                attrValue.value
            }
            else -> null
        }
    }
}
