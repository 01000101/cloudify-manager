/*******************************************************************************
 * Copyright (c) 2013 GigaSpaces Technologies Ltd. All rights reserved
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

package org.cloudifysource.cosmo.dsl;

import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.cloudifysource.cosmo.dsl.RelationshipTemplate.ExecutionListItem;

/**
 * Post processor the prepares the map for the workflow consumption.
 * Auto wires interfaces to plugins.
 *
 * @author Dan Kilman
 * @since 0.1
 */
public class PluginArtifactAwareDSLPostProcessor implements DSLPostProcessor {

    private static final String CLOUDIFY_TOSCA_PLUGIN = "cloudify.tosca.artifacts.plugin";
    private static final String CLOUDIFY_TOSCA_REMOTE_PLUGIN = "cloudify.tosca.artifacts.remote_plugin";
    private static final String CLOUDIFY_TOSCA_AGENT_PLUGIN = "cloudify.tosca.artifacts.agent_plugin";

    @Override
    public Map<String, Object> postProcess(Definitions definitions,
                                           Map<String, ApplicationTemplate> populatedServiceTemplates,
                                           Map<String, Plugin> populatedPlugins,
                                           Map<String, Relationship> populatedRelationships) {

        Map<String, Set<String>> interfacePluginImplementations =
                extractInterfacePluginImplementations(definitions.getInterfaces(), populatedPlugins);

        Map<String, Object> result = Maps.newHashMap();
        List<Object> nodes = Lists.newArrayList();
        Map<String, Object> nodesExtraData = Maps.newHashMap();
        Map<String, Map<String, Policy>> policies = Maps.newHashMap();

        for (ApplicationTemplate applicationTemplate : populatedServiceTemplates.values()) {
            for (TypeTemplate typeTemplate : applicationTemplate.getTopology()) {
                // Type template name we be prepended with the service template
                String nodeId = applicationTemplate.getName() + "." + typeTemplate.getName();
                typeTemplate.setName(nodeId);
                Map<String, Object> node = processTypeTemplateNode(
                        applicationTemplate.getName(),
                        typeTemplate,
                        definitions,
                        interfacePluginImplementations,
                        populatedPlugins);
                Map<String, Object> nodeExtraData = processTypeTemplateNodeExtraData(
                        applicationTemplate.getName(),
                        typeTemplate);
                nodes.add(node);
                nodesExtraData.put(nodeId, nodeExtraData);
                policies.put(nodeId, typeTemplate.getPolicies());
            }
        }

        result.put("nodes", nodes);
        result.put("nodes_extra", nodesExtraData);
        result.put("rules", definitions.getPolicies().getRules());
        result.put("policies", policies);
        result.put("policies_events", definitions.getPolicies().getTypes());
        result.put("relationships", populatedRelationships);
        return result;
    }

    private Map<String, Object> processTypeTemplateNodeExtraData(String serviceTemplateName,
                                                                 TypeTemplate typeTemplate) {
        Map<String, Object> nodeExtraData = Maps.newHashMap();

        setNodeSuperTypes(typeTemplate, nodeExtraData);

        setNodeFlattenedRelationships(typeTemplate, serviceTemplateName, nodeExtraData);

        return nodeExtraData;
    }


    private Map<String, Object> processTypeTemplateNode(String serviceTemplateName,
                                                        TypeTemplate typeTemplate,
                                                        Definitions definitions,
                                                        Map<String, Set<String>> interfacesToPluginImplementations,
                                                        Map<String, Plugin> populatedPlugins) {
        Map<String, Object> node = Maps.newHashMap();

        setNodeId(typeTemplate, node);

        setNodeProperties(typeTemplate, node);

        setNodeRelationships(typeTemplate, serviceTemplateName, node);

        setNodeWorkflows(typeTemplate, definitions, node);

        setNodePolicies(typeTemplate, node);

        setNodeOperationsAndPlugins(typeTemplate,
                                    definitions,
                                    interfacesToPluginImplementations,
                                    node,
                                    populatedPlugins);


        return node;
    }

    private Map<String, Set<String>> extractInterfacePluginImplementations(Map<String, Interface> interfaces,
                                                                           Map<String, Plugin> populatedPlugins) {
        Map<String, Set<String>> interfacePluginImplementations = Maps.newHashMap();
        for (String interfaceName : interfaces.keySet()) {
            interfacePluginImplementations.put(interfaceName, Sets.<String>newHashSet());
        }

        for (Plugin plugin : populatedPlugins.values()) {
            if (!plugin.isInstanceOf(CLOUDIFY_TOSCA_PLUGIN) ||
                    Objects.equal(CLOUDIFY_TOSCA_PLUGIN, plugin.getName())) {
                continue;
            }
            Preconditions.checkArgument(
                    plugin.isInstanceOf(
                            CLOUDIFY_TOSCA_REMOTE_PLUGIN) ||
                            plugin.isInstanceOf(CLOUDIFY_TOSCA_AGENT_PLUGIN),
                    "Plugin [%s] cannot be derived directly from [%s]", plugin.getName(),
                    CLOUDIFY_TOSCA_PLUGIN);
            if (!plugin.getProperties().containsKey("interface") ||
                !(plugin.getProperties().get("interface") instanceof String)) {
                continue;
            }
            String pluginInterface = (String) plugin.getProperties().get("interface");
            Set<String> implementingPlugins = interfacePluginImplementations.get(pluginInterface);
            if (implementingPlugins == null) {
                throw new IllegalArgumentException("Plugin references a non defined interface [" +
                        pluginInterface + "]");
            }
            implementingPlugins.add(plugin.getName());
            interfacePluginImplementations.put(pluginInterface, implementingPlugins);
        }
        return interfacePluginImplementations;
    }

    private void setNodeId(TypeTemplate typeTemplate, Map<String, Object> node) {
        node.put("id", typeTemplate.getName());
    }

    private void setNodeProperties(TypeTemplate typeTemplate, Map<String, Object> node) {
        node.put("properties", typeTemplate.getProperties());
    }

    private void setNodeRelationships(TypeTemplate typeTemplate, String serviceTemplateName, Map<String, Object> node) {
        List<Object> relationships = Lists.newLinkedList();
        for (RelationshipTemplate relationship : typeTemplate.getRelationships()) {
            Map<String, Object> relationshipMap = Maps.newHashMap();
            relationshipMap.put("type", relationship.getType());
            String fullTargetId = extractFullTargetIdFromRelationship(serviceTemplateName, relationship.getTarget());
            relationshipMap.put("target_id", fullTargetId);

            List<Map<String, String>> postTargetStart = Lists.newArrayList();
            for (Object rawItem : relationship.getPostTargetStart()) {
                postTargetStart.add(ExecutionListItem.fromObject(rawItem));
            }
            relationshipMap.put("post_target_start", postTargetStart);
            List<Map<String, String>> postSourceStart = Lists.newArrayList();
            for (Object rawItem : relationship.getPostSourceStart()) {
                postSourceStart.add(ExecutionListItem.fromObject(rawItem));
            }
            relationshipMap.put("post_source_start", postSourceStart);

            relationships.add(relationshipMap);
        }
        node.put("relationships", relationships);
    }



    private void setNodeWorkflows(TypeTemplate typeTemplate, Definitions definitions, Map<String, Object> node) {
        final Map<String, Object> workflows = Maps.newHashMap();
        for (Map.Entry<String, Workflow> entry : typeTemplate.getWorkflows().entrySet()) {
            workflows.put(entry.getKey(), entry.getValue().getRadial());
        }
        node.put("workflows", workflows);
    }

    private void setNodePolicies(TypeTemplate typeTemplate, Map<String, Object> node) {
        final Map<String, Policy> policies =
                typeTemplate.getPolicies() != null ? typeTemplate.getPolicies() : Maps.<String, Policy>newHashMap();
        node.put("policies", policies);
    }

    private void setNodeOperationsAndPlugins(TypeTemplate typeTemplate, Definitions definitions,
                                             Map<String, Set<String>> interfacesToPluginImplementations,
                                             Map<String, Object> node, Map<String, Plugin> populatedPlugins) {
        Set<String> sameNameOperations = Sets.newHashSet();
        Map<String, String> operationToPlugin = Maps.newHashMap();
        Map<String, Map<String, Object>> plugins = Maps.newHashMap();
        for (Object interfaceReference : typeTemplate.getInterfaces()) {

            Type.InterfaceDescription interfaceDescription = Type.InterfaceDescription.from(interfaceReference);

            // validate interface is define
            if (!definitions.getInterfaces().containsKey(interfaceDescription.getName())) {
                throw new IllegalArgumentException("Referencing non defined interface [" +
                        interfaceDescription.getName() + "]");
            }
            Interface theInterface = definitions.getInterfaces().get(interfaceDescription.getName());

            // find and validate exactly 1 matching plugin implementation
            Set<String> pluginImplementations = interfacesToPluginImplementations.get(interfaceDescription.getName());

            String pluginImplementation;
            if (interfaceDescription.getImplementation().isPresent()) {
                if (!pluginImplementations.contains(interfaceDescription.getImplementation().get())) {
                    throw new IllegalArgumentException("Explicit plugin [" + interfaceDescription.getImplementation()
                            .get() + "] not defined.");
                }
                pluginImplementation = interfaceDescription.getImplementation().get();
            } else {
                if (pluginImplementations.size() == 0) {
                    throw new IllegalArgumentException("No implementing plugin found for interface [" +
                            interfaceDescription.getName() + "]");
                }
                if (pluginImplementations.size() > 1) {
                    throw new IllegalArgumentException("More than 1 implementing plugin found for interface [" +
                            interfaceDescription.getName() + "]");
                }
                pluginImplementation = pluginImplementations.iterator().next();
            }

            Map<String, Object> pluginDetails = Maps.newHashMap();
            Plugin plugin = populatedPlugins.get(pluginImplementation);
            boolean agentPlugin = plugin.isInstanceOf(CLOUDIFY_TOSCA_AGENT_PLUGIN);
            pluginDetails.putAll(plugin.getProperties());
            pluginDetails.put("name", pluginImplementation);
            pluginDetails.put("agent_plugin", Boolean.toString(agentPlugin));
            plugins.put(pluginImplementation, pluginDetails);

            Set<String> operations = Sets.newHashSet(theInterface.getOperations());
            for (String operation : operations) {
                // always add fully qualified name to operation
                operationToPlugin.put(interfaceDescription.getName() + "." + operation, pluginImplementation);

                // now try adding operation name on its own to simplify usage in workflow.
                // if we already find one, remove it so there will be no ambiguities
                // if we already found a duplicate, ignore.
                if (sameNameOperations.contains(operation)) {
                    continue;
                }

                if (operationToPlugin.containsKey(operation)) {
                    operationToPlugin.remove(operation);
                    sameNameOperations.add(operation);
                } else {
                    operationToPlugin.put(operation, pluginImplementation);
                }
            }
        }
        node.put("plugins", plugins);
        node.put("operations", operationToPlugin);
    }

    private void setNodeSuperTypes(TypeTemplate typeTemplate, Map<String, Object> nodeExtraData) {
        nodeExtraData.put("super_types", typeTemplate.getSuperTypes());
    }

    private void setNodeFlattenedRelationships(TypeTemplate typeTemplate,
                                               String serviceTemplateName,
                                               Map<String, Object> nodeExtraData) {
        List<String> flattenedRelations = Lists.newArrayList();
        for (RelationshipTemplate relationship : typeTemplate.getRelationships()) {
            flattenedRelations.add(extractFullTargetIdFromRelationship(serviceTemplateName, relationship.getTarget()));
        }
        nodeExtraData.put("relationships", flattenedRelations);
    }

    private String extractFullTargetIdFromRelationship(String serviceTemplateName, String target) {
        return serviceTemplateName + "." + target;
    }

}
