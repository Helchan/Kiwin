package com.euver.kiwi.service

import com.euver.kiwi.parser.MyBatisXmlParser
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.xml.XmlFile
import com.intellij.ide.highlighter.XmlFileType

/**
 * Mapper 索引服务
 * 负责维护项目中 MyBatis Mapper 文件的索引,建立 namespace 到文件的映射
 * 
 * 使用 VirtualFile 作为缓存值，避免 PsiFile 失效问题
 */
@Service(Service.Level.PROJECT)
class MapperIndexService(private val project: Project) {

    private val logger = thisLogger()
    private val parser = MyBatisXmlParser()

    // namespace 到 VirtualFile 的映射缓存（使用 VirtualFile 避免 PsiFile 失效问题）
    private val namespaceToVirtualFileCache = mutableMapOf<String, VirtualFile>()
    
    // 标记是否已完成初始化扫描
    @Volatile
    private var initialized = false

    /**
     * 根据 namespace 查找对应的 Mapper XML 文件
     */
    fun findMapperFileByNamespace(namespace: String): XmlFile? {
        // 先尝试从缓存获取
        val cachedResult = getFromCacheIfValid(namespace)
        if (cachedResult != null) {
            return cachedResult
        }

        // 缓存未命中或失效，重新扫描索引
        rebuildIndex()
        
        // 再次尝试获取
        return getFromCacheIfValid(namespace)
    }
    
    /**
     * 从缓存获取有效的 XmlFile
     * 如果缓存的 VirtualFile 已失效，返回 null
     */
    private fun getFromCacheIfValid(namespace: String): XmlFile? {
        val virtualFile = namespaceToVirtualFileCache[namespace] ?: return null
        
        // 检查 VirtualFile 是否仍然有效
        if (!virtualFile.isValid) {
            logger.debug("Cached VirtualFile for namespace $namespace is invalid, removing from cache")
            namespaceToVirtualFileCache.remove(namespace)
            return null
        }
        
        // 获取 PsiFile
        val psiFile = PsiManager.getInstance(project).findFile(virtualFile) as? XmlFile
        if (psiFile == null) {
            logger.debug("Cannot get XmlFile for namespace $namespace, removing from cache")
            namespaceToVirtualFileCache.remove(namespace)
            return null
        }
        
        return psiFile
    }

    /**
     * 查找项目中所有的 Mapper XML 文件
     */
    fun findAllMapperFiles(): List<XmlFile> {
        val mapperFiles = mutableListOf<XmlFile>()
        val psiManager = PsiManager.getInstance(project)

        // 使用 FileTypeIndex 查找所有 XML 文件
        val xmlFiles = FileTypeIndex.getFiles(
            XmlFileType.INSTANCE,
            GlobalSearchScope.allScope(project)  // 使用 allScope 确保包含所有模块
        )

        for (virtualFile in xmlFiles) {
            if (!virtualFile.isValid) continue
            val psiFile = psiManager.findFile(virtualFile) as? XmlFile ?: continue
            if (isMapperFile(psiFile)) {
                mapperFiles.add(psiFile)
            }
        }

        logger.info("Found ${mapperFiles.size} MyBatis Mapper XML files in project")
        return mapperFiles
    }

    /**
     * 重新构建索引
     * 每次都完全重新扫描，确保获取最新状态
     */
    private fun rebuildIndex() {
        logger.info("Rebuilding MyBatis Mapper index...")
        val psiManager = PsiManager.getInstance(project)
        
        // 清空旧缓存
        namespaceToVirtualFileCache.clear()
        
        // 使用 FileTypeIndex 查找所有 XML 文件
        val xmlFiles = FileTypeIndex.getFiles(
            XmlFileType.INSTANCE,
            GlobalSearchScope.allScope(project)  // 使用 allScope 确保包含所有模块
        )

        for (virtualFile in xmlFiles) {
            if (!virtualFile.isValid) continue
            val psiFile = psiManager.findFile(virtualFile) as? XmlFile ?: continue
            if (isMapperFile(psiFile)) {
                val namespace = parser.extractNamespace(psiFile)
                if (namespace != null) {
                    namespaceToVirtualFileCache[namespace] = virtualFile
                }
            }
        }
        
        initialized = true
        logger.info("Index rebuilt: ${namespaceToVirtualFileCache.size} namespaces indexed")
    }

    /**
     * 清空缓存
     */
    fun clearCache() {
        namespaceToVirtualFileCache.clear()
        initialized = false
        logger.info("Mapper index cache cleared")
    }

    /**
     * 判断是否为 MyBatis Mapper 文件
     */
    private fun isMapperFile(xmlFile: XmlFile): Boolean {
        val rootTag = xmlFile.rootTag ?: return false
        return rootTag.name == "mapper" && rootTag.getAttributeValue("namespace") != null
    }

    companion object {
        /**
         * 获取服务实例
         */
        fun getInstance(project: Project): MapperIndexService {
            return project.getService(MapperIndexService::class.java)
        }
    }
}
