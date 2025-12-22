# Kiwi Changelog

## [0.0.57]
### Fixed
- 修复TopCallerFinderService在遇到Lambda表达式/函数式接口时调用链中断的问题
- 新增Lambda表达式声明位置追踪功能，支持追溯到Lambda声明所在的方法
- 新增方法引用(Class::method)追踪功能
- 新增JDK函数式接口方法过滤逻辑，过滤Consumer.accept、Function.apply等不应作为顶层调用者的接口方法
- 支持Spring框架常见回调接口的识别（TransactionCallback、RowMapper等）

## [0.0.56]
### Previous
- 初始版本功能

## [0.0.18]
- 将插件ID从 com.euver.template 更改为 com.euver.kiwi
- 清理冗余代码和未使用的模块
- 移除未使用的 IncludeRefInfo 模型类
- 更新项目文档

## [0.0.17]
- 优化控制台输出格式
- 修复循环引用检测逻辑

## [0.0.1]
- 初始版本
- 实现 Copy Expanded Statement 功能
- 支持 XML 文件和 Java Mapper 接口触发
- 支持跨文件 SQL 片段引用
- 支持循环引用检测
- 集成 Kiwi Console 控制台输出
