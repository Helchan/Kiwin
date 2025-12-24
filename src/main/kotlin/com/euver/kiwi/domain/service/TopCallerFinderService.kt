package com.euver.kiwi.domain.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiMethod
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.roots.TestSourcesFilter
import com.intellij.psi.PsiLambdaExpression
import com.intellij.psi.PsiMethodReferenceExpression
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.MethodReferencesSearch
import com.intellij.psi.search.searches.OverridingMethodsSearch
import com.intellij.psi.search.searches.FunctionalExpressionSearch
import com.intellij.psi.util.PsiTreeUtil
import java.util.concurrent.ConcurrentHashMap

/**
 * 顶层调用者查找服务
 * 负责从指定方法开始，向上追溯调用链，找到所有顶层调用者（入口方法）
 * 
 * 支持的调用链场景：
 * - 直接方法调用
 * - 接口/父类方法调用
 * - Lambda表达式调用（追踪到Lambda声明位置）
 * - 方法引用调用（Class::method，追踪到引用声明位置）
 */
class TopCallerFinderService(private val project: Project) {

    private val logger = thisLogger()
    
    // 方法键缓存，避免重复计算
    private val methodKeyCache = ConcurrentHashMap<PsiMethod, String>()
    
    // 生产代码搜索范围缓存
    @Volatile
    private var cachedProductionScope: GlobalSearchScope? = null

    companion object {
        private const val MAX_DEPTH = 50
        
        /**
         * 需要过滤的JDK函数式接口方法
         * 这些方法是函数式接口的抽象方法，不应被视为有意义的顶层调用者
         */
        private val FUNCTIONAL_INTERFACE_METHODS = setOf(
            // java.util.function
            "java.util.function.Consumer.accept",
            "java.util.function.BiConsumer.accept",
            "java.util.function.Function.apply",
            "java.util.function.BiFunction.apply",
            "java.util.function.Supplier.get",
            "java.util.function.Predicate.test",
            "java.util.function.BiPredicate.test",
            "java.util.function.UnaryOperator.apply",
            "java.util.function.BinaryOperator.apply",
            // java.lang
            "java.lang.Runnable.run",
            "java.util.concurrent.Callable.call",
            // Java Streams
            "java.util.stream.Stream.forEach",
            "java.util.stream.Stream.map",
            "java.util.stream.Stream.filter",
            "java.util.stream.Stream.flatMap",
            // 常见框架回调接口
            "org.springframework.transaction.support.TransactionCallback.doInTransaction",
            "org.springframework.jdbc.core.RowMapper.mapRow",
            "org.springframework.jdbc.core.ResultSetExtractor.extractData"
        )
    }

    /**
     * 查找指定方法的所有顶层调用者
     * @param method 起始方法
     * @return 顶层调用者方法集合（已去重，不包含起始方法本身）
     */
    fun findTopCallers(method: PsiMethod): Set<PsiMethod> {
        // 清理缓存，每次新的查找使用新的缓存
        clearCache()
        
        // 确保在 Smart Mode 下运行，避免 IndexNotReadyException
        val dumbService = DumbService.getInstance(project)
        if (dumbService.isDumb) {
            logger.warn("当前处于 Dumb 模式（索引中），等待索引完成...")
            // 等待索引完成，最多等待 30 秒
            dumbService.waitForSmartMode()
        }
        
        val methodFullName = ReadAction.compute<String, RuntimeException> {
            "${method.containingClass?.qualifiedName}.${method.name}"
        }
        logger.info("开始查找方法的顶层调用者: $methodFullName")
        
        val topCallers = mutableSetOf<PsiMethod>()
        val visited = mutableSetOf<String>()
        val sourceMethodKey = getMethodKeyCached(method)
        
        // 使用广度优先搜索（BFS）代替深度优先递归，减少栈深度和提高效率
        findTopCallersBFS(method, topCallers, visited)
        
        // 排除起始方法本身：如果起始方法没有调用者，不应该将它返回为顶层调用者
        val filteredCallers = topCallers.filter { getMethodKeyCached(it) != sourceMethodKey }.toSet()
        
        logger.info("找到 ${filteredCallers.size} 个顶层调用者，共访问了 ${visited.size} 个不同方法")
        return filteredCallers
    }
    
    /**
     * 清理缓存
     */
    private fun clearCache() {
        methodKeyCache.clear()
        cachedProductionScope = null
    }
    
    /**
     * 使用广度优先搜索查找顶层调用者
     * 优点：避免深度递归导致的栈溢出，更容易控制和取消
     */
    private fun findTopCallersBFS(
        startMethod: PsiMethod,
        topCallers: MutableSet<PsiMethod>,
        visited: MutableSet<String>
    ) {
        val queue = ArrayDeque<Pair<PsiMethod, Int>>() // 方法和当前深度
        queue.add(startMethod to 0)
        
        while (queue.isNotEmpty()) {
            // 检查是否被取消
            ProgressManager.checkCanceled()
            
            val (method, depth) = queue.removeFirst()
            
            if (depth > MAX_DEPTH) {
                val methodKey = getMethodKeyCached(method)
                logger.warn("达到最大递归深度限制: $MAX_DEPTH，当前方法: $methodKey")
                continue
            }
            
            val methodKey = getMethodKeyCached(method)
            if (methodKey in visited) {
                continue
            }
            visited.add(methodKey)
            
            // 检查是否是函数式接口方法
            if (isFunctionalInterfaceMethodCached(method)) {
                logger.debug("检测到函数式接口方法: $methodKey，尝试通过Lambda/方法引用追溯实际调用者")
                val lambdaCallers = findLambdaDeclarationCallers(method)
                if (lambdaCallers.isNotEmpty()) {
                    logger.debug("找到 ${lambdaCallers.size} 个通过Lambda/方法引用的调用者")
                    for (caller in lambdaCallers) {
                        queue.add(caller to depth + 1)
                    }
                    continue
                }
            }
            
            // 批量查找所有调用者
            val callers = findAllCallersBatched(method)
            
            if (callers.isEmpty()) {
                // 没有调用者，是顶层调用者
                if (!isFunctionalInterfaceMethodCached(method)) {
                    topCallers.add(method)
                    logger.debug("找到顶层调用者: $methodKey")
                }
            } else {
                // 将调用者加入队列继续搜索
                for (caller in callers) {
                    queue.add(caller to depth + 1)
                }
            }
        }
    }

    /**
     * 带缓存的函数式接口方法检查
     */
    private fun isFunctionalInterfaceMethodCached(method: PsiMethod): Boolean {
        val methodKey = getMethodKeyCached(method)
        // 简单判断：检查方法签名是否在函数式接口方法集合中
        val className = methodKey.substringAfter(" ").substringBefore(".")
        val methodName = methodKey.substringAfter(".").substringBefore("(")
        val fullName = "$className.$methodName"
        return fullName in FUNCTIONAL_INTERFACE_METHODS
    }
    
    /**
     * 批量查找所有调用者（优化版本）
     * 在单个 ReadAction 中完成所有查找操作
     */
    private fun findAllCallersBatched(method: PsiMethod): List<PsiMethod> {
        return ReadAction.compute<List<PsiMethod>, RuntimeException> {
            ProgressManager.checkCanceled()
            
            val allCallers = mutableSetOf<String>()
            val callerMethods = mutableListOf<PsiMethod>()
            val scope = getProductionScope()
            
            // 1. 查找该方法本身的直接调用者
            val directCallers = findDirectCallersInternal(method, scope)
            for (caller in directCallers) {
                val key = getMethodKeyInternal(caller)
                if (key !in allCallers) {
                    allCallers.add(key)
                    callerMethods.add(caller)
                }
            }
            
            // 2. 查找该方法所实现/重写的父类或接口方法的调用者
            val superMethods = method.findSuperMethods().toList()
            for (superMethod in superMethods) {
                ProgressManager.checkCanceled()
                val superCallers = findDirectCallersInternal(superMethod, scope)
                for (caller in superCallers) {
                    val key = getMethodKeyInternal(caller)
                    if (key !in allCallers) {
                        allCallers.add(key)
                        callerMethods.add(caller)
                    }
                }
            }
            
            callerMethods
        }
    }
    
    /**
     * 内部方法：在 ReadAction 上下文中查找直接调用者
     */
    private fun findDirectCallersInternal(method: PsiMethod, scope: GlobalSearchScope): List<PsiMethod> {
        ProgressManager.checkCanceled()
        
        val references = MethodReferencesSearch.search(method, scope, true).findAll()
        val projectFileIndex = ProjectRootManager.getInstance(project).fileIndex
        
        return references.mapNotNull { reference ->
            val callerMethod = PsiTreeUtil.getParentOfType(reference.element, PsiMethod::class.java)
            if (callerMethod != null) {
                val containingFile = callerMethod.containingFile?.virtualFile
                if (containingFile != null && !projectFileIndex.isInTestSourceContent(containingFile)) {
                    callerMethod
                } else {
                    null
                }
            } else {
                null
            }
        }.distinctBy { getMethodKeyInternal(it) }
    }
    
    /**
     * 内部方法：生成方法键（在 ReadAction 上下文中调用）
     */
    private fun getMethodKeyInternal(method: PsiMethod): String {
        // 先检查缓存
        methodKeyCache[method]?.let { return it }
        
        val className = method.containingClass?.qualifiedName ?: "UnknownClass"
        val methodName = method.name
        val returnType = method.returnType?.canonicalText ?: "void"
        val params = method.parameterList.parameters.joinToString(",") { 
            it.type.presentableText 
        }
        val key = "$returnType $className.$methodName($params)"
        
        // 缓存结果
        methodKeyCache[method] = key
        return key
    }

    /**
     * 获取缓存的生产代码搜索范围
     * 必须在 ReadAction 中调用
     */
    private fun getProductionScope(): GlobalSearchScope {
        cachedProductionScope?.let { return it }
        
        val projectScope = GlobalSearchScope.projectScope(project)
        val projectFileIndex = ProjectRootManager.getInstance(project).fileIndex
        
        val scope = projectScope.intersectWith(object : GlobalSearchScope(project) {
            override fun contains(file: com.intellij.openapi.vfs.VirtualFile): Boolean {
                return !projectFileIndex.isInTestSourceContent(file)
            }
            override fun isSearchInModuleContent(aModule: com.intellij.openapi.module.Module): Boolean = true
            override fun isSearchInLibraries(): Boolean = false
        })
        
        cachedProductionScope = scope
        return scope
    }

    /**
     * 查找Lambda表达式和方法引用的声明位置所在的方法
     * 用于追溯函数式接口调用的实际调用者
     */
    private fun findLambdaDeclarationCallers(method: PsiMethod): List<PsiMethod> {
        return ReadAction.compute<List<PsiMethod>, RuntimeException> {
            ProgressManager.checkCanceled()
            
            val callers = mutableListOf<PsiMethod>()
            val scope = getProductionScope()
            val projectFileIndex = ProjectRootManager.getInstance(project).fileIndex
            
            // 获取方法所在的接口/类
            val containingClass = method.containingClass ?: return@compute emptyList()
            
            // 查找所有实现该函数式接口的Lambda表达式和方法引用
            try {
                val functionalExpressions = FunctionalExpressionSearch.search(containingClass, scope).findAll()
                
                for (expression in functionalExpressions) {
                    ProgressManager.checkCanceled()
                    when (expression) {
                        is PsiLambdaExpression -> {
                            val enclosingMethod = PsiTreeUtil.getParentOfType(expression, PsiMethod::class.java)
                            if (enclosingMethod != null) {
                                val containingFile = enclosingMethod.containingFile?.virtualFile
                                if (containingFile != null && !projectFileIndex.isInTestSourceContent(containingFile)) {
                                    callers.add(enclosingMethod)
                                }
                            }
                        }
                        is PsiMethodReferenceExpression -> {
                            val enclosingMethod = PsiTreeUtil.getParentOfType(expression, PsiMethod::class.java)
                            if (enclosingMethod != null) {
                                val containingFile = enclosingMethod.containingFile?.virtualFile
                                if (containingFile != null && !projectFileIndex.isInTestSourceContent(containingFile)) {
                                    callers.add(enclosingMethod)
                                }
                            }
                        }
                    }
                }
            } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
                throw e // 重新抛出取消异常
            } catch (e: Exception) {
                logger.warn("查找Lambda/方法引用时出错: ${e.message}")
            }
            
            callers.distinctBy { getMethodKeyInternal(it) }
        }
    }

    /**
     * 带缓存的方法键获取
     * 自动处理 ReadAction 上下文
     */
    private fun getMethodKeyCached(method: PsiMethod): String {
        // 先检查缓存
        methodKeyCache[method]?.let { return it }
        
        // 缓存未命中，需要计算
        return ReadAction.compute<String, RuntimeException> {
            getMethodKeyInternal(method)
        }
    }

}
