package com.devai.devaiplatform.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.devai.devaiplatform.entity.WorkflowDefinition;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Agent 工作流执行引擎
 * 解析工作流定义，按拓扑顺序执行节点，支持串行/并行/条件分支
 */
@Service
public class WorkflowEngine {
    
    private final ChatLanguageModel chatModel;
    private final DevRagService ragService;
    private final PersistentMemoryService memoryService;
    private final LocalFileOperationService fileOperationService;
    private final WebSearchService webSearchService;
    private final ProjectStructureGenerator projectStructureGenerator;
    private final ScriptExecutorService scriptExecutorService;
    private final DevAgentService baseService;
    
    public WorkflowEngine(ChatLanguageModel chatModel,
                         DevRagService ragService,
                         PersistentMemoryService memoryService,
                         LocalFileOperationService fileOperationService,
                         WebSearchService webSearchService,
                         ProjectStructureGenerator projectStructureGenerator,
                         ScriptExecutorService scriptExecutorService,
                         DevAgentService baseService) {
        this.chatModel = chatModel;
        this.ragService = ragService;
        this.memoryService = memoryService;
        this.fileOperationService = fileOperationService;
        this.webSearchService = webSearchService;
        this.projectStructureGenerator = projectStructureGenerator;
        this.scriptExecutorService = scriptExecutorService;
        this.baseService = baseService;
    }
    
    /**
     * 执行工作流
     * @param workflow 工作流定义
     * @param taskContent 任务内容
     * @return 执行结果
     */
    public String execute(WorkflowDefinition workflow, String taskContent) {
        // 1. 解析节点和连线
        List<WorkflowDefinition.WorkflowNode> nodes = JSON.parseObject(
            workflow.getNodesJson(), 
            new TypeReference<List<WorkflowDefinition.WorkflowNode>>() {});
        
        List<WorkflowDefinition.WorkflowEdge> edges = JSON.parseObject(
            workflow.getEdgesJson(), 
            new TypeReference<List<WorkflowDefinition.WorkflowEdge>>() {});
        
        // 2. 构建邻接表（图结构）
        Map<String, List<String>> adjacencyList = buildAdjacencyList(edges);
        
        // 3. 拓扑排序（确定执行顺序）
        List<WorkflowDefinition.WorkflowNode> executionOrder = topologicalSort(nodes, edges);
        
        // 4. 按顺序执行节点
        Map<String, String> nodeResults = new LinkedHashMap<>();
        for (WorkflowDefinition.WorkflowNode node : executionOrder) {
            reportProgress("info", "执行节点: " + node.getLabel() + " (" + node.getType() + ")");
            
            String result = executeNode(node, taskContent, nodeResults);
            nodeResults.put(node.getId(), result);
            
            reportProgress("info", "节点完成: " + node.getLabel());
        }
        
        // 5. 聚合最终结果（从 END 节点或最后一个节点获取）
        return aggregateResults(executionOrder, nodeResults);
    }
    
    /**
     * 构建邻接表
     */
    private Map<String, List<String>> buildAdjacencyList(List<WorkflowDefinition.WorkflowEdge> edges) {
        Map<String, List<String>> adj = new HashMap<>();
        for (WorkflowDefinition.WorkflowEdge edge : edges) {
            adj.computeIfAbsent(edge.getSource(), k -> new ArrayList<>()).add(edge.getTarget());
        }
        return adj;
    }
    
    /**
     * 拓扑排序（Kahn算法）
     */
    private List<WorkflowDefinition.WorkflowNode> topologicalSort(
            List<WorkflowDefinition.WorkflowNode> nodes,
            List<WorkflowDefinition.WorkflowEdge> edges) {
        
        // 计算入度
        Map<String, Integer> inDegree = new HashMap<>();
        for (WorkflowDefinition.WorkflowNode node : nodes) {
            inDegree.put(node.getId(), 0);
        }
        for (WorkflowDefinition.WorkflowEdge edge : edges) {
            inDegree.merge(edge.getTarget(), 1, Integer::sum);
        }
        
        // BFS
        Queue<String> queue = new LinkedList<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.offer(entry.getKey());
            }
        }
        
        List<WorkflowDefinition.WorkflowNode> result = new ArrayList<>();
        Map<String, WorkflowDefinition.WorkflowNode> nodeMap = nodes.stream()
            .collect(Collectors.toMap(n -> n.getId(), n -> n));
        
        while (!queue.isEmpty()) {
            String nodeId = queue.poll();
            WorkflowDefinition.WorkflowNode node = nodeMap.get(nodeId);
            if (node != null) {
                result.add(node);
            }
            
            // 遍历邻居
            for (WorkflowDefinition.WorkflowEdge edge : edges) {
                if (edge.getSource().equals(nodeId)) {
                    String targetId = edge.getTarget();
                    inDegree.merge(targetId, -1, Integer::sum);
                    if (inDegree.get(targetId) == 0) {
                        queue.offer(targetId);
                    }
                }
            }
        }
        
        return result;
    }
    
    /**
     * 执行单个节点
     */
    private String executeNode(WorkflowDefinition.WorkflowNode node, 
                              String taskContent,
                              Map<String, String> prevResults) {
        
        switch (node.getType().toUpperCase()) {
            case "AGENT":
                return executeAgentNode(node, taskContent, prevResults);
            case "TOOL":
                return executeToolNode(node, taskContent, prevResults);
            case "CONDITION":
                return evaluateCondition(node, prevResults);
            case "MERGE":
                return mergeResults(node, prevResults);
            case "START":
                return "工作流开始";
            case "END":
                return "工作流结束";
            default:
                return "未知节点类型: " + node.getType();
        }
    }
    
    /**
     * 执行 Agent 节点
     */
    private String executeAgentNode(WorkflowDefinition.WorkflowNode node, 
                                   String taskContent,
                                   Map<String, String> prevResults) {
        
        String agentType = node.getAgentType();
        if (agentType == null || agentType.isBlank()) {
            return "错误: Agent类型未配置";
        }
        
        // 构建上下文（前序节点结果）
        String context = buildContext(prevResults);
        String fullTask = "任务: " + taskContent + "\n\n前序结果:\n" + context;
        
        switch (agentType.toUpperCase()) {
            case "RESEARCH":
                return executeResearchAgent(fullTask);
            case "CODE":
                return executeCodeAgent(fullTask);
            case "FILE":
                return executeFileAgent(fullTask);
            case "PROJECT":
                return executeProjectAgent(fullTask);
            case "DIRECT":
                return chatModel.generate(taskContent);
            default:
                return "未知Agent类型: " + agentType;
        }
    }
    
    /**
     * 执行工具节点
     */
    private String executeToolNode(WorkflowDefinition.WorkflowNode node,
                                  String taskContent,
                                  Map<String, String> prevResults) {
        
        String toolName = node.getToolName();
        if (toolName == null || toolName.isBlank()) {
            return "错误: 工具名称未配置";
        }
        
        Map<String, Object> config = node.getConfig();
        if (config == null) config = new HashMap<>();
        
        try {
            switch (toolName) {
                case "searchDevLib":
                    String question = (String) config.getOrDefault("question", taskContent);
                    return ragService.retrieveRelevantContent(question);
                    
                case "searchWeb":
                    String query = (String) config.getOrDefault("query", taskContent);
                    var searchResult = webSearchService.search(query);
                    return searchResult.toPromptContext();
                    
                case "retrieveMemories":
                    String memQuery = (String) config.getOrDefault("query", taskContent);
                    return memoryService.getRelevantMemories(memQuery);
                    
                case "createDirectory":
                    String dirPath = (String) config.getOrDefault("dirPath", "");
                    var dirResult = fileOperationService.createDirectory(dirPath);
                    return dirResult.success ? "✅ " + dirResult.message : "❌ " + dirResult.message;
                    
                case "createFile":
                    String filePath = (String) config.getOrDefault("filePath", "");
                    String content = (String) config.getOrDefault("content", "");
                    var fileResult = fileOperationService.createFile(filePath, content);
                    return fileResult.success ? "✅ " + fileResult.message : "❌ " + fileResult.message;
                    
                case "readFile":
                    String readPath = (String) config.getOrDefault("filePath", "");
                    return fileOperationService.readFile(readPath);
                    
                default:
                    return "未知工具: " + toolName;
            }
        } catch (Exception e) {
            return "工具执行失败: " + e.getMessage();
        }
    }
    
    /**
     * 评估条件节点
     */
    private String evaluateCondition(WorkflowDefinition.WorkflowNode node,
                                    Map<String, String> prevResults) {
        
        Map<String, Object> config = node.getConfig();
        if (config == null) return "true";
        
        String expression = (String) config.getOrDefault("expression", "true");
        
        // 简单条件评估（可扩展为更复杂的表达式引擎）
        try {
            // 替换变量引用 ${nodeId}
            for (Map.Entry<String, String> entry : prevResults.entrySet()) {
                expression = expression.replace("${" + entry.getKey() + "}", entry.getValue());
            }
            
            // 简单的布尔表达式评估
            if (expression.equalsIgnoreCase("true")) return "true";
            if (expression.equalsIgnoreCase("false")) return "false";
            
            // 检查是否包含特定关键词
            if (expression.startsWith("contains:")) {
                String keyword = expression.substring(9);
                String lastResult = prevResults.values().stream()
                    .reduce((first, second) -> second)
                    .orElse("");
                return String.valueOf(lastResult.contains(keyword));
            }
            
            return "true"; // 默认通过
        } catch (Exception e) {
            return "条件评估失败: " + e.getMessage();
        }
    }
    
    /**
     * 合并多个分支的结果
     */
    private String mergeResults(WorkflowDefinition.WorkflowNode node,
                               Map<String, String> prevResults) {
        
        Map<String, Object> config = node.getConfig();
        String strategy = config != null ? (String) config.getOrDefault("strategy", "concat") : "concat";
        
        StringBuilder merged = new StringBuilder();
        
        switch (strategy) {
            case "concat":
                // 拼接所有前序结果
                for (String result : prevResults.values()) {
                    if (result != null && !result.isBlank()) {
                        merged.append(result).append("\n\n");
                    }
                }
                break;
                
            case "last":
                // 只取最后一个结果
                prevResults.values().stream()
                    .reduce((first, second) -> second)
                    .ifPresent(r -> merged.append(r));
                break;
                
            case "first":
                // 只取第一个非空结果
                prevResults.values().stream()
                    .filter(r -> r != null && !r.isBlank())
                    .findFirst()
                    .ifPresent(r -> merged.append(r));
                break;
        }
        
        return merged.toString();
    }
    
    /**
     * 执行研究Agent
     */
    private String executeResearchAgent(String task) {
        reportProgress("tool", "📚 研究Agent执行中...");
        
        // 简化版：直接调用 RAG 查询
        String question = extractQuestion(task);
        String memories = memoryService.getRelevantMemories(question);
        return ragService.ragQuery(question + "\n历史参考:\n" + memories);
    }
    
    /**
     * 执行代码Agent
     */
    private String executeCodeAgent(String task) {
        reportProgress("tool", "💻 代码Agent执行中...");
        return chatModel.generate(task);
    }
    
    /**
     * 执行文件Agent
     */
    private String executeFileAgent(String task) {
        reportProgress("tool", "📁 文件Agent执行中...");
        return chatModel.generate(task);
    }
    
    /**
     * 执行项目Agent
     */
    private String executeProjectAgent(String task) {
        reportProgress("tool", "🏗️ 项目Agent执行中...");
        return chatModel.generate(task);
    }
    
    /**
     * 构建上下文字符串
     */
    private String buildContext(Map<String, String> prevResults) {
        if (prevResults.isEmpty()) return "无前序结果";
        
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : prevResults.entrySet()) {
            sb.append("- ").append(entry.getKey()).append(": ")
              .append(truncate(entry.getValue(), 200)).append("\n");
        }
        return sb.toString();
    }
    
    /**
     * 聚合最终结果
     */
    private String aggregateResults(List<WorkflowDefinition.WorkflowNode> executionOrder,
                                   Map<String, String> nodeResults) {
        
        // 查找 END 节点
        Optional<WorkflowDefinition.WorkflowNode> endNode = executionOrder.stream()
            .filter(n -> "END".equalsIgnoreCase(n.getType()))
            .findFirst();
        
        if (endNode.isPresent()) {
            String endResult = nodeResults.get(endNode.get().getId());
            if (endResult != null && !endResult.isBlank()) {
                return endResult;
            }
        }
        
        // 如果没有END节点，返回最后一个节点的结果
        if (!executionOrder.isEmpty()) {
            WorkflowDefinition.WorkflowNode lastNode = executionOrder.get(executionOrder.size() - 1);
            return nodeResults.getOrDefault(lastNode.getId(), "工作流执行完成");
        }
        
        return "工作流执行完成";
    }
    
    /**
     * 从任务文本中提取问题
     */
    private String extractQuestion(String task) {
        // 简单提取：去掉"任务:"前缀
        if (task.startsWith("任务:")) {
            int newlineIdx = task.indexOf('\n');
            if (newlineIdx > 0) {
                return task.substring(3, newlineIdx).trim();
            }
            return task.substring(3).trim();
        }
        return task;
    }
    
    /**
     * 截断字符串
     */
    private String truncate(String str, int maxLen) {
        if (str == null) return "";
        return str.length() > maxLen ? str.substring(0, maxLen) + "..." : str;
    }
    
    /**
     * 报告进度
     */
    private void reportProgress(String type, String msg) {
        baseService.reportProgressPublic(type, msg);
    }
}
