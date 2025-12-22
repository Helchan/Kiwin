package com.euver.kiwi.domain.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
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
        val methodFullName = ApplicationManager.getApplication().runReadAction<String> {
            "${method.containingClass?.qualifiedName}.${method.name}"
        }
        logger.info("开始查找方法的顶层调用者: $methodFullName")
        
        val topCallers = mutableSetOf<PsiMethod>()
        val visited = mutableSetOf<String>()
        val sourceMethodKey = getMethodKey(method)
        
        findTopCallersRecursively(method, topCallers, visited, 0)
        
        // 排除起始方法本身：如果起始方法没有调用者，不应该将它返回为顶层调用者
        val filteredCallers = topCallers.filter { getMethodKey(it) != sourceMethodKey }.toSet()
        
        logger.info("找到 ${filteredCallers.size} 个顶层调用者，共访问了 ${visited.size} 个不同方法")
        return filteredCallers
    }

    /**
     * 递归查找顶层调用者
     */
    private fun findTopCallersRecursively(
        method: PsiMethod,
        topCallers: MutableSet<PsiMethod>,
        visited: MutableSet<String>,
        depth: Int
    ) {
        if (depth > MAX_DEPTH) {
            val methodKey = getMethodKey(method)
            logger.warn("达到最大递归深度限制: $MAX_DEPTH，当前方法: $methodKey，可能存在过深的调用链或循环引用")
            return
        }

        val methodKey = getMethodKey(method)
        if (methodKey in visited) {
            logger.debug("方法已访问，跳过: $methodKey")
            return
        }
        visited.add(methodKey)

        // 检查是否是函数式接口方法，如果是则需要特殊处理
        if (isFunctionalInterfaceMethod(method)) {
            logger.info("检测到函数式接口方法: $methodKey，尝试通过Lambda/方法引用追溯实际调用者")
            // 对于函数式接口方法，尝试查找Lambda和方法引用的声明位置
            val lambdaCallers = findLambdaDeclarationCallers(method)
            if (lambdaCallers.isNotEmpty()) {
                logger.info("找到 ${lambdaCallers.size} 个通过Lambda/方法引用的调用者")
                for (caller in lambdaCallers) {
                    findTopCallersRecursively(caller, topCallers, visited, depth + 1)
                }
                return
            }
            // 如果找不到Lambda调用者，不将函数式接口方法作为顶层调用者，继续常规搜索
            logger.info("未找到Lambda/方法引用的调用者，尝试常规调用链追溯")
        }

        // 查找该方法及其所实现/重写的接口或父类方法的所有调用者
        val callers = findAllCallers(method)

        if (callers.isEmpty()) {
            // 如果是函数式接口方法，不将其作为顶层调用者
            if (!isFunctionalInterfaceMethod(method)) {
                topCallers.add(method)
                logger.debug("找到顶层调用者: $methodKey")
            } else {
                logger.debug("跳过函数式接口方法作为顶层调用者: $methodKey")
            }
        } else {
            logger.debug("方法 $methodKey 有 ${callers.size} 个调用者，继续向上追溯")
            for (caller in callers) {
                findTopCallersRecursively(caller, topCallers, visited, depth + 1)
            }
        }
    }

    /**
     * 判断方法是否是函数式接口方法
     */
    private fun isFunctionalInterfaceMethod(method: PsiMethod): Boolean {
        return ApplicationManager.getApplication().runReadAction<Boolean> {
            val className = method.containingClass?.qualifiedName ?: return@runReadAction false
            val methodName = method.name
            val fullName = "$className.$methodName"
            fullName in FUNCTIONAL_INTERFACE_METHODS
        }
    }

    /**
     * 查找Lambda表达式和方法引用的声明位置所在的方法
     * 用于追溯函数式接口调用的实际调用者
     */
    private fun findLambdaDeclarationCallers(method: PsiMethod): List<PsiMethod> {
        val callers = mutableListOf<PsiMethod>()
        val scope = createProductionScope()
        
        ApplicationManager.getApplication().runReadAction {
            // 获取方法所在的接口/类
            val containingClass = method.containingClass ?: return@runReadAction
            
            // 查找所有实现该函数式接口的Lambda表达式和方法引用
            try {
                val functionalExpressions = FunctionalExpressionSearch.search(containingClass, scope).findAll()
                
                for (expression in functionalExpressions) {
                    when (expression) {
                        is PsiLambdaExpression -> {
                            // Lambda表达式：查找包含它的方法
                            val enclosingMethod = PsiTreeUtil.getParentOfType(expression, PsiMethod::class.java)
                            if (enclosingMethod != null && !isInTestSource(enclosingMethod)) {
                                callers.add(enclosingMethod)
                                logger.debug("Lambda表达式声明位置: ${getMethodKey(enclosingMethod)}")
                            }
                        }
                        is PsiMethodReferenceExpression -> {
                            // 方法引用：查找包含它的方法
                            val enclosingMethod = PsiTreeUtil.getParentOfType(expression, PsiMethod::class.java)
                            if (enclosingMethod != null && !isInTestSource(enclosingMethod)) {
                                callers.add(enclosingMethod)
                                logger.debug("方法引用声明位置: ${getMethodKey(enclosingMethod)}")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                logger.warn("查找Lambda/方法引用时出错: ${e.message}")
            }
        }
        
        return callers.distinctBy { getMethodKey(it) }
    }

    /**
     * 查找方法的所有调用者（包括通过接口或父类调用的情况）
     * 关键点：当实现类方法重写了接口方法时，调用者可能调用的是接口方法
     * 因此需要同时搜索该方法及其所实现的父接口/父类方法的调用者
     */
    private fun findAllCallers(method: PsiMethod): List<PsiMethod> {
        val allCallers = mutableSetOf<String>()
        val callerMethods = mutableListOf<PsiMethod>()

        // 1. 查找该方法本身的直接调用者
        val directCallers = findDirectCallers(method)
        for (caller in directCallers) {
            val key = getMethodKey(caller)
            if (key !in allCallers) {
                allCallers.add(key)
                callerMethods.add(caller)
            }
        }

        // 2. 查找该方法所实现/重写的父类或接口方法的调用者
        val superMethods = findSuperMethods(method)
        for (superMethod in superMethods) {
            val superCallers = findDirectCallers(superMethod)
            for (caller in superCallers) {
                val key = getMethodKey(caller)
                if (key !in allCallers) {
                    allCallers.add(key)
                    callerMethods.add(caller)
                }
            }
        }

        return callerMethods
    }

    /**
     * 查找方法所实现或重写的所有父类/接口方法
     */
    private fun findSuperMethods(method: PsiMethod): List<PsiMethod> {
        return ApplicationManager.getApplication().runReadAction<List<PsiMethod>> {
            method.findSuperMethods().toList()
        }
    }

    /**
     * 查找直接调用指定方法的所有方法
     * 注意：只搜索生产代码，排除测试代码
     */
    private fun findDirectCallers(method: PsiMethod): List<PsiMethod> {
        val scope = createProductionScope()
        val references = ApplicationManager.getApplication().runReadAction<Collection<com.intellij.psi.PsiReference>> {
            MethodReferencesSearch.search(method, scope, true).findAll()
        }

        return references.mapNotNull { reference ->
            ApplicationManager.getApplication().runReadAction<PsiMethod?> {
                val callerMethod = PsiTreeUtil.getParentOfType(reference.element, PsiMethod::class.java)
                // 再次过滤，确保调用者不在测试源码中
                if (callerMethod != null && !isInTestSource(callerMethod)) {
                    callerMethod
                } else {
                    null
                }
            }
        }.distinctBy { getMethodKey(it) }
    }

    /**
     * 创建生产代码搜索范围（排除测试目录）
     */
    private fun createProductionScope(): GlobalSearchScope {
        return ApplicationManager.getApplication().runReadAction<GlobalSearchScope> {
            val projectScope = GlobalSearchScope.projectScope(project)
            val projectFileIndex = ProjectRootManager.getInstance(project).fileIndex
            
            // 过滤掉测试源码目录
            projectScope.intersectWith(object : GlobalSearchScope(project) {
                override fun contains(file: com.intellij.openapi.vfs.VirtualFile): Boolean {
                    return !projectFileIndex.isInTestSourceContent(file)
                }

                override fun isSearchInModuleContent(aModule: com.intellij.openapi.module.Module): Boolean = true
                override fun isSearchInLibraries(): Boolean = false
            })
        }
    }

    /**
     * 判断方法是否在测试源码中
     */
    private fun isInTestSource(method: PsiMethod): Boolean {
        return ApplicationManager.getApplication().runReadAction<Boolean> {
            val containingFile = method.containingFile?.virtualFile ?: return@runReadAction false
            val projectFileIndex = ProjectRootManager.getInstance(project).fileIndex
            projectFileIndex.isInTestSourceContent(containingFile)
        }
    }

    /**
     * 生成方法的唯一标识键
     * 包含返回类型和参数类型签名以确保唯一性，避免重载方法被错误去重
     */
    private fun getMethodKey(method: PsiMethod): String {
        return ApplicationManager.getApplication().runReadAction<String> {
            val className = method.containingClass?.qualifiedName ?: "UnknownClass"
            val methodName = method.name
            val returnType = method.returnType?.canonicalText ?: "void"
            val params = method.parameterList.parameters.joinToString(",") { 
                // 使用 presentableText 保留泛型信息，避免类型擦除导致的冲突
                it.type.presentableText 
            }
            "$returnType $className.$methodName($params)"
        }
    }
}
