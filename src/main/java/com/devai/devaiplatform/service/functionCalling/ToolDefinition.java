package com.devai.devaiplatform.service.functionCalling;

import java.util.List;
import java.util.Map;

/**
 * 函数/工具定义模型 - 描述可被AI调用的函数
 * 用于 Function Calling 系统中动态注册和发现工具
 */
public class ToolDefinition {

    /**
     * 函数名称（唯一标识，AI会用它来发起调用）
     */
    private String name;

    /**
     * 函数描述（AI理解何时调用此函数）
     */
    private String description;

    /**
     * 参数JSON Schema定义
     */
    private Parameters parameters;

    public ToolDefinition() {
    }

    public ToolDefinition(String name, String description, Parameters parameters) {
        this.name = name;
        this.description = description;
        this.parameters = parameters;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Parameters getParameters() {
        return parameters;
    }

    public void setParameters(Parameters parameters) {
        this.parameters = parameters;
    }

    /**
     * 参数JSON Schema定义
     */
    public static class Parameters {
        /**
         * 参数类型（通常为 "object"）
         */
        private String type = "object";

        /**
         * 属性定义 Map<属性名, 属性Schema>
         */
        private Map<String, Property> properties;

        /**
         * 必需参数列表
         */
        private List<String> required;

        public Parameters() {
        }

        public Parameters(Map<String, Property> properties, List<String> required) {
            this.properties = properties;
            this.required = required;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public Map<String, Property> getProperties() {
            return properties;
        }

        public void setProperties(Map<String, Property> properties) {
            this.properties = properties;
        }

        public List<String> getRequired() {
            return required;
        }

        public void setRequired(List<String> required) {
            this.required = required;
        }
    }

    /**
     * 单个属性的Schema定义
     */
    public static class Property {
        /**
         * 属性类型：string, number, integer, boolean, array, object
         */
        private String type;

        /**
         * 属性描述
         */
        private String description;

        /**
         * 枚举值限制（可选）
         */
        private List<String> enumValues;

        /**
         * 数组项类型（当type=array时使用）
         */
        private String itemsType;

        public Property() {
        }

        public Property(String type, String description) {
            this.type = type;
            this.description = description;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public List<String> getEnumValues() {
            return enumValues;
        }

        public void setEnumValues(List<String> enumValues) {
            this.enumValues = enumValues;
        }

        public String getItemsType() {
            return itemsType;
        }

        public void setItemsType(String itemsType) {
            this.itemsType = itemsType;
        }
    }

    /**
     * 将内置工具定义转换为 OpenAI-compatible 的 JSON 格式
     */
    public Map<String, Object> toOpenAITool() {
        Map<String, Object> function = new java.util.LinkedHashMap<>();
        function.put("name", name);
        function.put("description", description);

        Map<String, Object> parametersMap = new java.util.LinkedHashMap<>();
        parametersMap.put("type", parameters.getType());

        // 构建 properties
        Map<String, Object> propsMap = new java.util.LinkedHashMap<>();
        if (parameters.getProperties() != null) {
            for (Map.Entry<String, Property> entry : parameters.getProperties().entrySet()) {
                Map<String, Object> propMap = new java.util.LinkedHashMap<>();
                propMap.put("type", entry.getValue().getType());
                propMap.put("description", entry.getValue().getDescription());
                if (entry.getValue().getEnumValues() != null && !entry.getValue().getEnumValues().isEmpty()) {
                    propMap.put("enum", entry.getValue().getEnumValues());
                }
                propsMap.put(entry.getKey(), propMap);
            }
        }
        parametersMap.put("properties", propsMap);

        if (parameters.getRequired() != null && !parameters.getRequired().isEmpty()) {
            parametersMap.put("required", parameters.getRequired());
        }

        function.put("parameters", parametersMap);

        Map<String, Object> tool = new java.util.LinkedHashMap<>();
        tool.put("type", "function");
        tool.put("function", function);
        return tool;
    }
}
