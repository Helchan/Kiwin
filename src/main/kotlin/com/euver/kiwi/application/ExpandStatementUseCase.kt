package com.euver.kiwi.application

import com.euver.kiwi.domain.service.SqlFragmentResolver
import com.euver.kiwi.domain.service.StatementExpanderService
import com.euver.kiwi.infrastructure.resolver.SqlFragmentResolverImpl
import com.euver.kiwi.model.AssemblyResult
import com.euver.kiwi.model.StatementInfo
import com.euver.kiwi.model.StatementType
import com.euver.kiwi.parser.MyBatisXmlParser
import com.euver.kiwi.service.MapperIndexService
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag

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
     * 从 Mapper 方法名展开 Statement
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
