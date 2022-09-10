/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2022 Martin d'Anjou
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.jenkinsci.plugins.pipeline.utility.steps.template;

import edu.umd.cs.findbugs.annotations.NonNull;

import groovy.text.SimpleTemplateEngine;
import groovy.text.Template;

import hudson.FilePath;
import hudson.Functions;
import hudson.model.Item;
import hudson.model.TaskListener;

import org.apache.commons.io.IOUtils;
import org.jenkinsci.plugins.pipeline.utility.steps.AbstractFileOrTextStepExecution;
import org.jenkinsci.plugins.scriptsecurity.sandbox.Whitelist;
import org.jenkinsci.plugins.scriptsecurity.sandbox.groovy.GroovySandbox;
import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.ProxyWhitelist;
import org.jenkinsci.plugins.scriptsecurity.scripts.ApprovalContext;
import org.jenkinsci.plugins.scriptsecurity.scripts.ScriptApproval;
import org.jenkinsci.plugins.scriptsecurity.scripts.languages.GroovyLanguage;
import org.jenkinsci.plugins.workflow.steps.StepContext;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.apache.commons.lang.StringUtils.isNotBlank;

/**
 * Execution of {@link SimpleTemplateEngineStep}.
 *
 * @author Martin d'Anjou
 */
public class SimpleTemplateEngineStepExecution extends AbstractFileOrTextStepExecution<Object> {
    private static final long serialVersionUID = 1L;

    private transient SimpleTemplateEngineStep step;

    protected SimpleTemplateEngineStepExecution(@NonNull SimpleTemplateEngineStep step, @NonNull StepContext context) {
        super(step, context);
        this.step = step;
    }

    @Override
    protected String doRun() throws Exception {
        TaskListener listener = getContext().get(TaskListener.class);
        PrintStream logger = listener.getLogger();

        String fName = step.getDescriptor().getFunctionName();
        if (isNotBlank(step.getFile()) && isNotBlank(step.getText())) {
            throw new IllegalArgumentException(Messages.SimpleTemplateEngineStepExecution_tooManyArguments(fName));
        }
        
        if (step.getBindings() == null) {
            throw new IllegalArgumentException(Messages.SimpleTemplateEngineStepExecution_missingBindings(fName));
        }

        SimpleTemplateEngine engine = new SimpleTemplateEngine();
        String templateText = "";
        if (isNotBlank(step.getFile())) {
            FilePath f = ws.child(step.getFile());
            if (f.exists() && !f.isDirectory()) {
                try (InputStream is = f.read()) {
                    templateText = IOUtils.toString(is, StandardCharsets.UTF_8);
                }
            } else if (f.isDirectory()) {
                throw new IllegalArgumentException(Messages.SimpleTemplateEngineStepExecution_fileIsDirectory(f.getRemote()));
            } else if (!f.exists()) {
                throw new FileNotFoundException(Messages.SimpleTemplateEngineStepExecution_fileNotFound(f.getRemote()));
	        }
        } else if (isNotBlank(step.getText())) {
            templateText = step.getText().trim();
        }

        final String templateTextFinal = templateText;
        final Map<String, Object> bindings = step.getBindings();

        String renderedTemplate = "";
        try {
            if (!step.isRunInSandbox()) {
                logger.println("simpleTemplateEngine running in script approval mode");
                ScriptApproval.get().configuring(templateTextFinal, GroovyLanguage.get(), ApprovalContext.create().withItem(getContext().get(Item.class)));
                Template template = engine.createTemplate(templateTextFinal);
                renderedTemplate = template.make(bindings).toString();
            } else {
                logger.println("simpleTemplateEngine running in sandbox mode");
                renderedTemplate = GroovySandbox.runInSandbox(
                    () -> {
                        final Template template = engine.createTemplate(templateTextFinal);
                        return template.make(bindings).toString();
                    },
                    new ProxyWhitelist(Whitelist.all())
                );

            }
        } catch (Exception ex) {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            Functions.printStackTrace(ex, pw);
            renderedTemplate = "Exception raised during template rendering: " + ex.getMessage() + "\n\n" + sw;
        }
        return renderedTemplate;
    }
}

