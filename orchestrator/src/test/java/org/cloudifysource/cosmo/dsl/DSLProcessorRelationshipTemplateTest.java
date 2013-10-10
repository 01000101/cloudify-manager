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

import org.testng.annotations.Test;

import java.util.List;

import static org.fest.assertions.api.Assertions.assertThat;

/**
 * TODO: Write a short summary of this type's roles and responsibilities.
 *
 * @author Dan Kilman
 * @since 0.1
 */
public class DSLProcessorRelationshipTemplateTest extends AbstractDSLProcessorTest {

    @Test
    public void testRelationshipTemplate() {

        String dslPath = "org/cloudifysource/cosmo/dsl/unit/relationship_templates/" +
                "dsl-with-relationship-templates.yaml";

        Processed processed = process(dslPath);

        List<ProcessedRelationshipTemplate> relationshipTemplates1 = findNode(processed.getNodes(),
                "service_template.webserver1").getRelationships();

        List<ProcessedRelationshipTemplate> relationshipTemplates2 = findNode(processed.getNodes(),
                "service_template.webserver2").getRelationships();

        List<ProcessedRelationshipTemplate> relationshipTemplates3 = findNode(processed.getNodes(),
                "service_template.webserver3").getRelationships();

        assertThat(relationshipTemplates1.size()).isEqualTo(1);
        assertThat(relationshipTemplates2.size()).isEqualTo(1);
        assertThat(relationshipTemplates3.size()).isEqualTo(3);

        assertThat(relationshipTemplates1.get(0).getType()).isEqualTo("cloudify.tosca.relationships.contained_in");
        assertThat(relationshipTemplates1.get(0).getTargetId()).isEqualTo("service_template.host1");

        assertThat(relationshipTemplates2.get(0).getType()).isEqualTo("cloudify.tosca.relationships.contained_in");
        assertThat(relationshipTemplates2.get(0).getTargetId()).isEqualTo("service_template.host1");

        assertThat(relationshipTemplates3.get(0).getTargetId()).isEqualTo("service_template.host1");
        assertThat(relationshipTemplates3.get(1).getTargetId()).isEqualTo("service_template.host2");
        assertThat(relationshipTemplates3.get(2).getTargetId()).isEqualTo("service_template.host3");

    }

    @Test
    public void testRelationshipInterfaceAndTemplate() {

        String dslPath = "org/cloudifysource/cosmo/dsl/unit/relationship_templates/" +
                "dsl-with-relationship-interface.yaml";

        Processed processed = process(dslPath);

        Node node1 = findNode(processed.getNodes(), "service_template.webserver");
        List<ProcessedRelationshipTemplate> relationshipTemplates1 = node1.getRelationships();

        Node node2 = findNode(processed.getNodes(), "service_template.webapplication");
        List<ProcessedRelationshipTemplate> relationshipTemplates2 = node2.getRelationships();

        assertThat(relationshipTemplates1.size()).isEqualTo(1);
        assertThat(relationshipTemplates2.size()).isEqualTo(1);

        assertThat(relationshipTemplates1.get(0).getType()).isEqualTo("relationship1");
        assertThat(relationshipTemplates1.get(0).getTargetId()).isEqualTo("service_template.host");
        assertThat(relationshipTemplates1.get(0).getPlugin()).isEqualTo("plugin1");
        assertThat(relationshipTemplates1.get(0).getBindLocation()).isEqualTo("source");
        assertThat(relationshipTemplates1.get(0).getBindTime()).isEqualTo("pre_started");

        assertThat(relationshipTemplates2.get(0).getType()).isEqualTo("relationship2");
        assertThat(relationshipTemplates2.get(0).getTargetId()).isEqualTo("service_template.webserver");
        assertThat(relationshipTemplates2.get(0).getPlugin()).isEqualTo("plugin2");
        assertThat(relationshipTemplates2.get(0).getBindLocation()).isEqualTo("target");
        assertThat(relationshipTemplates2.get(0).getBindTime()).isEqualTo("post_started");

        // Test that we place the right plugins under the right node during processing
        // based on bind_location (source/target)
        assertThat(node1.getPlugins()).containsKey("plugin1");
        assertThat(node1.getPlugins()).containsKey("plugin2");

        assertThat(processed.getRelationships().get("relationship1").getInterface().getName()).isEqualTo("interface1");
        assertThat(processed.getRelationships().get("relationship2").getInterface().getName()).isEqualTo("interface2");

        assertThat(processed.getRelationships().get("relationship1").getWorkflow().getRadial()).isEqualTo("workflow1");
        assertThat(processed.getRelationships().get("relationship2").getWorkflow().getRadial()).isEqualTo("workflow2");

    }


    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testRelationshipTemplateInvalidType() {
        String dslPath = "org/cloudifysource/cosmo/dsl/unit/relationship_templates/" +
                "dsl-with-relationship-templates-invalid-type.yaml";
        process(dslPath);
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testRelationshipTemplateInvalidTarget() {
        String dslPath = "org/cloudifysource/cosmo/dsl/unit/relationship_templates/" +
                "dsl-with-relationship-templates-invalid-target.yaml";
        process(dslPath);
    }

    @Test
    public void testRelationshipTemplateWorkflowOverride() {
        String dslPath =
                "org/cloudifysource/cosmo/dsl/unit/relationship_templates/dsl-with-relationship-wf-override.yaml";
        Processed processed = process(dslPath);
        final Node node = findNode(processed.getNodes(), "app.server");
        assertThat(node.getRelationships().get(0).getWorkflow()).isEqualTo("overridden_workflow");
        assertThat(node.getRelationships().get(1).getWorkflow()).isEqualTo("some_workflow");
    }

}
