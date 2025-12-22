package com.euver.kiwi.service

import com.euver.kiwi.domain.model.MethodInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.pom.Navigatable
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.awt.BorderLayout
import java.awt.Desktop
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicReference
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.table.DefaultTableModel

/**
 * 顶层调用者表格展示对话框
 */
class TopCallersTableDialog(
    private val project: Project,
    private val sourceMethodName: String,
    private val topCallers: List<MethodInfo>
) : DialogWrapper(project) {

    private val logger = thisLogger()
    private lateinit var tableModel: DefaultTableModel
    private val columnNames = arrayOf(
        "序号",
        "类型",
        "请求路径",
        "方法",
        "类功能注释",
        "方法功能注释",
        "包路径"
    )

    init {
        title = "顶层调用者列表"
        isModal = false
        init()
    }
    
    override fun createActions(): Array<Action> {
        val exportAction = object : DialogWrapperAction("EXPORT") {
            override fun doAction(e: ActionEvent?) {
                exportToExcel()
            }
        }
        return arrayOf(okAction, exportAction)
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())

        tableModel = object : DefaultTableModel(columnNames, 0) {
            override fun isCellEditable(row: Int, column: Int): Boolean = false
        }

        topCallers.forEachIndexed { index, methodInfo ->
            val type = if (methodInfo.isExternalInterface()) "API" else "Normal"
            val methodDisplay = "${methodInfo.simpleClassName}.${methodInfo.methodSignature}"

            tableModel.addRow(
                arrayOf(
                    index + 1,
                    type,
                    methodInfo.requestPath,
                    methodDisplay,
                    methodInfo.classComment,
                    methodInfo.functionComment,
                    methodInfo.packageName
                )
            )
        }

        val table = JBTable(tableModel)
        table.autoCreateRowSorter = true
        
        // 启用单元格选择模式
        table.cellSelectionEnabled = true
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)

        val popupMenu = JPopupMenu()
        val navigateItem = JMenuItem("跳转到源码")
        val copyItem = JMenuItem("复制")
        popupMenu.add(navigateItem)
        popupMenu.add(copyItem)

        navigateItem.addActionListener {
            val selectedRow = table.selectedRow
            if (selectedRow >= 0) {
                val modelRow = table.convertRowIndexToModel(selectedRow)
                val methodInfo = topCallers[modelRow]
                navigateToMethod(methodInfo)
            }
        }
        
        copyItem.addActionListener {
            copySelectedCells(table)
        }
        
        // 注册 Ctrl+C / Command+C 快捷键
        val copyKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_C, 
            java.awt.Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx)
        table.getInputMap(JComponent.WHEN_FOCUSED).put(copyKeyStroke, "copy")
        table.actionMap.put("copy", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                copySelectedCells(table)
            }
        })

        table.addMouseListener(object : MouseAdapter() {
            private fun handlePopup(e: MouseEvent) {
                if (e.isPopupTrigger) {
                    val row = table.rowAtPoint(e.point)
                    val col = table.columnAtPoint(e.point)
                    if (row >= 0 && col >= 0) {
                        // 如果点击的单元格不在当前选择范围内，则选中该单元格
                        if (!table.isCellSelected(row, col)) {
                            table.setRowSelectionInterval(row, row)
                            table.setColumnSelectionInterval(col, col)
                        }
                        popupMenu.show(e.component, e.x, e.y)
                    }
                }
            }

            override fun mousePressed(e: MouseEvent) = handlePopup(e)
            override fun mouseReleased(e: MouseEvent) = handlePopup(e)
        })

        panel.add(JBScrollPane(table), BorderLayout.CENTER)
        return panel
    }

    override fun createNorthPanel(): JComponent? {
        val panel = JPanel(BorderLayout())
        panel.add(JLabel("源位置：$sourceMethodName"), BorderLayout.WEST)
        return panel
    }

    override fun getDimensionServiceKey(): String? {
        return "Kiwi.TopCallers.TableDialog"
    }

    private fun navigateToMethod(methodInfo: MethodInfo) {
        val app = ApplicationManager.getApplication()
        val psiMethodRef = AtomicReference<PsiMethod?>()

        app.runReadAction {
            psiMethodRef.set(findPsiMethod(methodInfo))
        }

        val psiMethod = psiMethodRef.get() ?: return

        app.invokeLater {
            (psiMethod as? Navigatable)?.navigate(true)
        }
    }

    private fun findPsiMethod(methodInfo: MethodInfo): PsiMethod? {
        val qualifiedName = methodInfo.qualifiedName
        val lastDot = qualifiedName.lastIndexOf('.')
        if (lastDot <= 0 || lastDot == qualifiedName.length - 1) {
            return null
        }

        val classFqn = qualifiedName.substring(0, lastDot)
        val methodName = extractMethodName(methodInfo.methodSignature)
        val paramTypes = extractParamTypes(methodInfo.methodSignature)

        val psiFacade = JavaPsiFacade.getInstance(project)
        val scope = GlobalSearchScope.projectScope(project)
        val psiClass = psiFacade.findClass(classFqn, scope) ?: return null

        val candidates = psiClass.findMethodsByName(methodName, false)
        if (candidates.isEmpty()) {
            return null
        }

        if (paramTypes.isEmpty()) {
            return candidates.firstOrNull()
        }

        for (candidate in candidates) {
            val psiParams = candidate.parameterList.parameters
            if (psiParams.size != paramTypes.size) {
                continue
            }
            var allMatch = true
            for (i in paramTypes.indices) {
                if (psiParams[i].type.presentableText != paramTypes[i]) {
                    allMatch = false
                    break
                }
            }
            if (allMatch) {
                return candidate
            }
        }

        return candidates.firstOrNull()
    }

    private fun extractMethodName(signature: String): String {
        return signature.substringBefore("(").trim()
    }

    private fun extractParamTypes(signature: String): List<String> {
        val paramsPart = signature.substringAfter("(", "").substringBeforeLast(")")
        if (paramsPart.isBlank()) {
            return emptyList()
        }
        return paramsPart.split(",").map { it.trim() }
    }
    
    /**
     * 复制选中的单元格内容到系统剪贴板
     * 保持表格的结构化格式，使用 Tab 分隔列，换行符分隔行
     */
    private fun copySelectedCells(table: JBTable) {
        val selectedRows = table.selectedRows
        val selectedColumns = table.selectedColumns
        
        if (selectedRows.isEmpty() || selectedColumns.isEmpty()) {
            return
        }
        
        val sb = StringBuilder()
        
        for (row in selectedRows) {
            val rowData = mutableListOf<String>()
            for (col in selectedColumns) {
                val value = table.getValueAt(row, col)?.toString() ?: ""
                rowData.add(value)
            }
            sb.append(rowData.joinToString("\t"))
            sb.append("\n")
        }
        
        val content = sb.toString().trimEnd('\n')
        CopyPasteManager.getInstance().setContents(StringSelection(content))
    }
    
    /**
     * 导出表格内容到 Excel 文件
     */
    private fun exportToExcel() {
        try {
            val outDir = createOutputDirectory()
            if (outDir == null) {
                NotificationService(project).showErrorNotification("创建导出目录失败")
                ConsoleOutputService(project).output("导出失败：无法创建 .Kiwi/out 目录")
                return
            }
            
            val fileName = generateFileName()
            val file = File(outDir, fileName)
            
            XSSFWorkbook().use { workbook ->
                val sheet = workbook.createSheet("顶层调用者")
                
                // 创建表头样式（浅蓝色背景）
                val headerStyle = workbook.createCellStyle().apply {
                    fillForegroundColor = IndexedColors.PALE_BLUE.index
                    fillPattern = FillPatternType.SOLID_FOREGROUND
                    val font = workbook.createFont().apply {
                        bold = true
                    }
                    setFont(font)
                }
                
                // 创建表头行
                val headerRow = sheet.createRow(0)
                columnNames.forEachIndexed { index, name ->
                    val cell = headerRow.createCell(index)
                    cell.setCellValue(name)
                    cell.cellStyle = headerStyle
                }
                
                // 填充数据行
                for (rowIndex in 0 until tableModel.rowCount) {
                    val row = sheet.createRow(rowIndex + 1)
                    for (colIndex in 0 until tableModel.columnCount) {
                        val cell = row.createCell(colIndex)
                        val value = tableModel.getValueAt(rowIndex, colIndex)?.toString() ?: ""
                        cell.setCellValue(value)
                    }
                }
                
                // 设置列宽度（自适应，最大 50 个字符宽度）
                val maxCharWidth = 50
                val maxColumnWidth = maxCharWidth * 256  // POI 使用 1/256 字符单位
                for (colIndex in 0 until tableModel.columnCount) {
                    sheet.autoSizeColumn(colIndex)
                    if (sheet.getColumnWidth(colIndex) > maxColumnWidth) {
                        sheet.setColumnWidth(colIndex, maxColumnWidth)
                    }
                }
                
                // 写入文件
                FileOutputStream(file).use { fos ->
                    workbook.write(fos)
                }
            }
            
            logger.info("导出成功：${file.absolutePath}")
            ConsoleOutputService(project).output("导出成功：${file.absolutePath}")
            NotificationService(project).showInfoNotification("导出成功")
            
            // 打开文件所在目录
            openDirectory(outDir)
            
        } catch (e: Exception) {
            logger.error("导出 Excel 失败", e)
            ConsoleOutputService(project).output("导出失败：${e.message}")
            NotificationService(project).showErrorNotification("导出失败：${e.message}")
        }
    }
    
    /**
     * 创建输出目录 .Kiwi/out
     */
    private fun createOutputDirectory(): File? {
        val projectBasePath = project.basePath ?: return null
        val outDir = File(projectBasePath, ".Kiwi/out")
        return if (outDir.exists() || outDir.mkdirs()) outDir else null
    }
    
    /**
     * 生成文件名
     * 格式：{StatementID或类名.方法名}_{YYYYMMDD_HHMMSS}.xlsx
     */
    private fun generateFileName(): String {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val baseName = sanitizeFileName(sourceMethodName)
        return "${baseName}_${timestamp}.xlsx"
    }
    
    /**
     * 清理文件名中的非法字符
     */
    private fun sanitizeFileName(name: String): String {
        // 移除 "SQL Fragment: " 前缀
        val cleanName = name.removePrefix("SQL Fragment: ")
        // 替换文件名中的非法字符
        return cleanName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }
    
    /**
     * 打开文件所在目录
     */
    private fun openDirectory(directory: File) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(directory)
            }
        } catch (e: Exception) {
            logger.warn("无法打开目录：${e.message}")
        }
    }
}
