package com.euver.kiwin.application

import com.euver.kiwin.domain.service.SqlFragmentResolver
import com.euver.kiwin.domain.service.StatementExpanderService
import com.euver.kiwin.infrastructure.resolver.SqlFragmentResolverImpl
import com.euver.kiwin.model.AssemblyResult
import com.euver.kiwin.model.StatementInfo
import com.euver.kiwin.model.StatementType
import com.euver.kiwin.parser.MyBatisXmlParser
import com.euver.kiwin.service.MapperIndexService
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import java.util.ArrayDeque

/**
 * 展开 Statement 用例
 * 应用层服务，协调领域服务和基础设施层完成 Statement 展开功能
 * 
 * 提供统一的入口供各种触发方式（Action、API 等）调用
 */
class ExpandStatementUseCase(private val project: Project) {
    
    private val logger = thisLogger()
    private val parser = MyBatisXmlParser()
    private val fragmentResolver: SqlFragmentResolver = SqlFragmentResolverImpl(project, parser)
    private val expanderService = StatementExpanderService(fragmentResolver)
    private val indexService by lazy { MapperIndexService.getInstance(project) }
    
    /**
     * 展开指定的 Statement
     * 
     * @param statementInfo Statement 信息
     * @return 组装结果
     */
    fun execute(statementInfo: StatementInfo): AssemblyResult {
        logger.info("执行 Statement 展开用例: ${statementInfo.namespace}.${statementInfo.statementId}")
        return expanderService.expand(statementInfo)
    }
    
    /**
     * 从 XML 标签展开 Statement
     * 
     * @param tag XML 标签
     * @param xmlFile XML 文件
     * @return 组装结果，如果标签不是有效的 Statement 则返回 null
     */
    fun executeFromTag(tag: XmlTag, xmlFile: XmlFile): AssemblyResult? {
        val statementInfo = parser.extractStatementFromTag(tag, xmlFile)
        if (statementInfo == null) {
            logger.warn("无法从标签提取 Statement 信息")
            return null
        }
        return execute(statementInfo)
    }
    
    /**
     * 从 Mapper 方法名获取所有可能的 Statement 候选（支持继承链 + 多 XML 文件）
     * 
     * 关键改进：
     * 1. 按继承链顺序（子接口 → 父接口）枚举所有接口
     * 2. 对于每个接口的 namespace，扫描所有匹配的 Mapper XML 文件（支持同 namespace 多文件场景）
     * 3. 返回所有匹配的 StatementInfo，由调用方决定如何处理
     * 
     * @param callerClass 调用者接口类型（从 qualifier 获取）
     * @param methodName 方法名
     * @return 所有匹配的 StatementInfo 列表，可能来自不同的 XML 文件（如 .postgresql.xml 和 .opengauss.xml）
     */
    fun findCandidateStatementsWithInheritance(callerClass: PsiClass, methodName: String): List<StatementInfo> {
        // 收集继承链中的所有接口（按从子到父的顺序）
        val interfaceChain = collectInterfaceChain(callerClass)
        
        // 一次性获取所有 Mapper XML 文件，避免多次扫描
        val allMapperFiles = indexService.findAllMapperFiles()
        
        val candidates = mutableListOf<StatementInfo>()
        
        logger.info("开始在继承链中查找 Statement 候选: $methodName, 继承链: ${interfaceChain.mapNotNull { it.qualifiedName }.joinToString(" -> ")}")
        
        for (interfaceClass in interfaceChain) {
            val namespace = interfaceClass.qualifiedName ?: continue
            
            // 筛选所有 namespace 匹配的 XML 文件（可能有多份：postgresql.xml / opengauss.xml）
            val xmlFilesForNamespace = allMapperFiles.filter { xml ->
                parser.extractNamespace(xml) == namespace
            }
            
            // 在每个 XML 文件中查找指定方法名的 Statement
            for (xmlFile in xmlFilesForNamespace) {
                val statementInfo = parser.findStatement(xmlFile, methodName)
                if (statementInfo != null) {
                    candidates.add(statementInfo)
                    logger.info("找到 Statement 候选: $namespace.$methodName 在 ${xmlFile.name}")
                }
            }
        }
        
        if (candidates.isEmpty()) {
            val triedNamespaces = interfaceChain.mapNotNull { it.qualifiedName }.joinToString(" -> ")
            logger.warn("未找到对应的 Statement: $methodName (已尝试 namespace: $triedNamespaces)")
        } else {
            logger.info("为 $methodName 找到 ${candidates.size} 个 Statement 候选")
        }
        
        return candidates
    }
    
    /**
     * 从 Mapper 方法名展开 Statement（支持继承链查找）
     * 
     * 关键改进：从调用者接口类型开始，按继承链顺序（子接口 → 父接口）查找对应的 Statement
     * 解决了方法定义在父接口、但 XML namespace 在子接口的场景
     * 
     * @param callerClass 调用者接口类型（从 qualifier 获取）
     * @param methodName 方法名
     * @return 组装结果，按继承链顺序查找直到找到对应 Statement
     */
    fun executeFromMapperMethodWithInheritance(callerClass: PsiClass, methodName: String): AssemblyResult? {
        // 收集继承链中的所有接口（按从子到父的顺序）
        val interfaceChain = collectInterfaceChain(callerClass)
        
        logger.info("开始在继承链中查找 Statement: $methodName, 继承链: ${interfaceChain.mapNotNull { it.qualifiedName }.joinToString(" -> ")}")
        
        for (interfaceClass in interfaceChain) {
            val namespace = interfaceClass.qualifiedName ?: continue
            
            // 尝试在当前接口对应的 XML 中查找
            val xmlFile = indexService.findMapperFileByNamespace(namespace) ?: continue
            val statementInfo = parser.findStatement(xmlFile, methodName)
            
            if (statementInfo != null) {
                logger.info("在继承链中找到 Statement: $namespace.$methodName")
                return execute(statementInfo)
            }
        }
        
        // 记录详细日志
        val triedNamespaces = interfaceChain.mapNotNull { it.qualifiedName }.joinToString(" -> ")
        logger.warn("未找到对应的 Statement: $methodName (已尝试: $triedNamespaces)")
        return null
    }
    
    /**
     * 收集接口的继承链（BFS，按从子到父的顺序）
     * 使用广度优先搜索确保层级顺序
     */
    private fun collectInterfaceChain(startClass: PsiClass): List<PsiClass> {
        val result = mutableListOf<PsiClass>()
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<PsiClass>()
        
        queue.add(startClass)
        
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val qualifiedName = current.qualifiedName ?: continue
            
            if (qualifiedName in visited) continue
            visited.add(qualifiedName)
            result.add(current)
            
            // 添加父接口到队列
            for (superInterface in current.interfaces) {
                queue.add(superInterface)
            }
        }
        
        return result
    }
    
    /**
     * 从 Mapper 方法名展开 Statement（不支持继承链查找，保留用于其他场景）
     * 
     * @param namespace Mapper 接口的全限定名
     * @param methodName 方法名
     * @return 组装结果，如果找不到对应 Statement 则返回 null
     */
    fun executeFromMapperMethod(namespace: String, methodName: String): AssemblyResult? {
        // 查找对应的 XML 文件
        val xmlFile = indexService.findMapperFileByNamespace(namespace)
        if (xmlFile == null) {
            logger.warn("未找到对应的 Mapper XML 文件: $namespace")
            return null
        }
        
        // 查找对应的 Statement
        val statementInfo = parser.findStatement(xmlFile, methodName)
        if (statementInfo == null) {
            logger.warn("未找到对应的 Statement: $methodName (Mapper: $namespace)")
            return null
        }
        
        return execute(statementInfo)
    }
    
    /**
     * 从 SQL 片段 ID 展开片段内容
     * 支持光标在 <sql> 的 id 或 <include> 的 refid 上触发
     * 
     * @param fragmentRefId 片段 ID（可能包含 namespace 前缀）
     * @param currentFile 当前 XML 文件
     * @return 组装结果，如果找不到片段则返回 null
     */
    fun executeFromSqlFragmentId(fragmentRefId: String, currentFile: XmlFile): AssemblyResult? {
        val currentNamespace = parser.extractNamespace(currentFile)
        if (currentNamespace == null) {
            logger.warn("无法从当前文件提取 namespace")
            return null
        }
        
        // 解析 refid，可能包含 namespace 前缀
        val (targetNamespace, fragmentId) = fragmentResolver.parseRefId(fragmentRefId, currentNamespace)
        
        // 查找 SQL 片段
        val fragment = fragmentResolver.findSqlFragment(targetNamespace, fragmentId, currentFile)
        if (fragment == null) {
            logger.warn("未找到 SQL 片段: $fragmentRefId (namespace: $targetNamespace)")
            return null
        }
        
        logger.info("执行 SQL 片段展开用例: ${fragment.namespace}.${fragment.fragmentId}")
        
        // 将 SQL 片段转换为 StatementInfo 进行展开
        val statementInfo = StatementInfo(
            statementId = fragment.fragmentId,
            statementType = StatementType.SELECT,  // SQL 片段没有类型，使用默认值
            namespace = fragment.namespace,
            content = fragment.content,
            sourceFile = fragment.sourceFile
        )
        
        return expanderService.expand(statementInfo)
    }
    
    /**
     * 展开指定内容
     * 简化版本，只展开内容不返回完整的组装结果
     * 
     * @param content 原始内容
     * @param currentFile 当前 XML 文件
     * @param currentNamespace 当前 namespace
     * @return 展开后的内容
     */
    fun expandContent(content: String, currentFile: XmlFile, currentNamespace: String): String {
        return expanderService.expandContent(content, currentFile, currentNamespace)
    }
    
    /**
     * 获取领域服务实例
     * 供需要直接使用领域服务的场景使用
     */
    fun getExpanderService(): StatementExpanderService {
        return expanderService
    }
}
