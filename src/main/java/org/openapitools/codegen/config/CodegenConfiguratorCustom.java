/*
 * Copyright 2018 OpenAPI-Generator Contributors (https://openapi-generator.tech)
 * Copyright 2018 SmartBear Software
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.openapitools.codegen.config;

import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.core.models.AuthorizationValue;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.apache.commons.lang3.Validate;
import org.openapitools.codegen.*;
import org.openapitools.codegen.api.TemplateDefinition;
import org.openapitools.codegen.api.TemplatingEngineAdapter;
import org.openapitools.codegen.auth.AuthParser;
import org.openapitools.codegen.languages.TypescriptExpressServerServerCodegen;
import org.openapitools.codegen.utils.ModelUtils;

import java.util.*;

import static org.apache.commons.lang3.StringUtils.isEmpty;
import static org.apache.commons.lang3.StringUtils.isNotEmpty;

public class CodegenConfiguratorCustom extends CodegenConfigurator {
    private List<TemplateDefinition> userDefinedTemplates = new ArrayList<>();
    private String generatorName;
    private String inputSpec;
    private GeneratorSettings.Builder generatorSettingsBuilder = GeneratorSettings.newBuilder();
    private WorkflowSettings.Builder workflowSettingsBuilder = WorkflowSettings.newBuilder();
    private String templatingEngineName;
    private String auth;

    public CodegenConfigurator setGeneratorName(final String generatorName) {
        this.generatorName = generatorName;
        generatorSettingsBuilder.withGeneratorName(generatorName);
        return this;
    }

    public CodegenConfigurator setInputSpec(String inputSpec) {
        this.inputSpec = inputSpec;
        workflowSettingsBuilder.withInputSpec(inputSpec);
        return this;
    }

    public CodegenConfigurator setOutputDir(String outputDir) {
        workflowSettingsBuilder.withOutputDir(outputDir);
        return this;
    }

    @Override
    public Context<?> toContext() {
        Validate.notEmpty(generatorName, "generator name must be specified");
        Validate.notEmpty(inputSpec, "input spec must be specified");

        GeneratorSettings generatorSettings = generatorSettingsBuilder.build();
//        CodegenConfig config = CodegenConfigLoader.forName(generatorSettings.getGeneratorName());
        CodegenConfig config = new TypescriptExpressServerServerCodegen();
        if (isEmpty(templatingEngineName)) {
            // if templatingEngineName is empty check the config for a default
            String defaultTemplatingEngine = config.defaultTemplatingEngine();
            workflowSettingsBuilder.withTemplatingEngineName(defaultTemplatingEngine);
        } else {
            workflowSettingsBuilder.withTemplatingEngineName(templatingEngineName);
        }

        // at this point, all "additionalProperties" are set, and are now immutable per GeneratorSettings instance.
        WorkflowSettings workflowSettings = workflowSettingsBuilder.build();

        if (workflowSettings.isVerbose()) {
            LOGGER.info("\nVERBOSE MODE: ON. Additional debug options are injected"
                    + "\n - [debugOpenAPI] prints the OpenAPI specification as interpreted by the codegen"
                    + "\n - [debugModels] prints models passed to the template engine"
                    + "\n - [debugOperations] prints operations passed to the template engine"
                    + "\n - [debugSupportingFiles] prints additional data passed to the template engine");

            GlobalSettings.setProperty("debugOpenAPI", "");
            GlobalSettings.setProperty("debugModels", "");
            GlobalSettings.setProperty("debugOperations", "");
            GlobalSettings.setProperty("debugSupportingFiles", "");
            GlobalSettings.setProperty("verbose", "true");
        } else {
            GlobalSettings.setProperty("verbose", "false");
        }

        for (Map.Entry<String, String> entry : workflowSettings.getGlobalProperties().entrySet()) {
            GlobalSettings.setProperty(entry.getKey(), entry.getValue());
        }

        // if caller resets GlobalSettings, we'll need to reset generateAliasAsModel. As noted in this method, this should be moved.
        ModelUtils.setGenerateAliasAsModel(workflowSettings.isGenerateAliasAsModel());

        // TODO: Support custom spec loader implementations (https://github.com/OpenAPITools/openapi-generator/issues/844)
        final List<AuthorizationValue> authorizationValues = AuthParser.parse(this.auth);
        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        SwaggerParseResult result = new OpenAPIParser().readLocation(inputSpec, authorizationValues, options);

        // TODO: Move custom validations to a separate type as part of a "Workflow"
        Set<String> validationMessages = new HashSet<>(null != result.getMessages() ? result.getMessages() : new ArrayList<>());
        OpenAPI specification = result.getOpenAPI();
        // TODO: The line below could be removed when at least one of the issue below has been resolved.
        // https://github.com/swagger-api/swagger-parser/issues/1369
        // https://github.com/swagger-api/swagger-parser/pull/1374
        //ModelUtils.getOpenApiVersion(specification, inputSpec, authorizationValues);

        // NOTE: We will only expose errors+warnings if there are already errors in the spec.
        if (validationMessages.size() > 0) {
            Set<String> warnings = new HashSet<>();
            if (specification != null) {

                // Wrap the getUnusedSchemas() in try catch block so it catches the NPE
                // when the input spec file is not correct
                try {
                    List<String> unusedModels = ModelUtils.getUnusedSchemas(specification);
                    if (unusedModels != null) {
                        unusedModels.forEach(name -> warnings.add("Unused model: " + name));
                    }
                } catch (Exception e) {
                    System.err.println("[error] There is an error with OpenAPI specification parsed from the input spec file: " + inputSpec);
                    System.err.println("[error] Please make sure the spec file has correct format and all required fields are populated with valid value.");
                }
            }

            if (workflowSettings.isValidateSpec()) {
                String sb = "There were issues with the specification. The option can be disabled via validateSpec (Maven/Gradle) or --skip-validate-spec (CLI)." +
                        System.lineSeparator();
                SpecValidationException ex = new SpecValidationException(sb);
                ex.setErrors(validationMessages);
                ex.setWarnings(warnings);
                throw ex;
            } else {
                StringBuilder sb = new StringBuilder();
                sb.append(
                        "There were issues with the specification, but validation has been explicitly disabled.");
                sb.append(System.lineSeparator());

                sb.append("Errors: ").append(System.lineSeparator());
                validationMessages.forEach(
                        msg -> sb.append("\t-").append(msg).append(System.lineSeparator()));

                if (!warnings.isEmpty()) {
                    sb.append("Warnings: ").append(System.lineSeparator());
                    warnings.forEach(
                            msg -> sb.append("\t-").append(msg).append(System.lineSeparator()));
                }
                LOGGER.warn(sb.toString());
            }
        }

        return new Context<>(specification, generatorSettings, workflowSettings);
    }

    @Override
    public ClientOptInput toClientOptInput() {
        Context<?> context = toContext();
        WorkflowSettings workflowSettings = context.getWorkflowSettings();
        GeneratorSettings generatorSettings = context.getGeneratorSettings();

        // We load the config via generatorSettings.getGeneratorName() because this is guaranteed to be set
        // regardless of entrypoint (CLI sets properties on this type, config deserialization sets on generatorSettings).
//        CodegenConfig config = CodegenConfigLoader.forName(generatorSettings.getGeneratorName());
        CodegenConfig config = new TypescriptExpressServerServerCodegen();

        if (isNotEmpty(generatorSettings.getLibrary())) {
            config.setLibrary(generatorSettings.getLibrary());
        }

        // TODO: Work toward CodegenConfig having a "WorkflowSettings" property, or better a "Workflow" object which itself has a "WorkflowSettings" property.
        config.setInputSpec(workflowSettings.getInputSpec());
        config.setOutputDir(workflowSettings.getOutputDir());
        config.setSkipOverwrite(workflowSettings.isSkipOverwrite());
        config.setIgnoreFilePathOverride(workflowSettings.getIgnoreFileOverride());
        config.setRemoveOperationIdPrefix(workflowSettings.isRemoveOperationIdPrefix());
        config.setSkipOperationExample(workflowSettings.isSkipOperationExample());
        config.setEnablePostProcessFile(workflowSettings.isEnablePostProcessFile());
        config.setEnableMinimalUpdate(workflowSettings.isEnableMinimalUpdate());
        config.setStrictSpecBehavior(workflowSettings.isStrictSpecBehavior());

        TemplatingEngineAdapter templatingEngine = TemplatingEngineLoader.byIdentifier(workflowSettings.getTemplatingEngineName());
        config.setTemplatingEngine(templatingEngine);

        // TODO: Work toward CodegenConfig having a "GeneratorSettings" property.
        config.instantiationTypes().putAll(generatorSettings.getInstantiationTypes());
        config.typeMapping().putAll(generatorSettings.getTypeMappings());
        config.importMapping().putAll(generatorSettings.getImportMappings());
        config.schemaMapping().putAll(generatorSettings.getSchemaMappings());
        config.inlineSchemaNameMapping().putAll(generatorSettings.getInlineSchemaNameMappings());
        config.inlineSchemaNameDefault().putAll(generatorSettings.getInlineSchemaNameDefaults());
        config.languageSpecificPrimitives().addAll(generatorSettings.getLanguageSpecificPrimitives());
        config.reservedWordsMappings().putAll(generatorSettings.getReservedWordsMappings());
        config.additionalProperties().putAll(generatorSettings.getAdditionalProperties());

        Map<String, String> serverVariables = generatorSettings.getServerVariables();
        if (!serverVariables.isEmpty()) {
            // This is currently experimental due to vagueness in the specification
            LOGGER.warn("user-defined server variable support is experimental.");
            config.serverVariableOverrides().putAll(serverVariables);
        }

        // any other additional properties?
        String templateDir = workflowSettings.getTemplateDir();
        if (templateDir != null) {
            config.additionalProperties().put(CodegenConstants.TEMPLATE_DIR, workflowSettings.getTemplateDir());
        }

        ClientOptInput input = new ClientOptInput()
                .config(config)
                .userDefinedTemplates(userDefinedTemplates);

        return input.openAPI((OpenAPI) context.getSpecDocument());
    }
}
