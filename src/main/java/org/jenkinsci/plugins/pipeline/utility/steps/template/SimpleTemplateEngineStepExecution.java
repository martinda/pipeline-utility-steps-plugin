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

import groovy.lang.Binding;
import groovy.lang.Script;
import groovy.lang.GroovyShell;
import groovy.text.SimpleTemplateEngine;
import groovy.text.Template;

import hudson.FilePath;
import hudson.Functions;
import hudson.model.Item;
import hudson.model.TaskListener;

import jenkins.model.Jenkins;

import org.apache.commons.io.IOUtils;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.customizers.ImportCustomizer;
import org.jenkinsci.plugins.pipeline.utility.steps.AbstractFileOrTextStepExecution;
import org.jenkinsci.plugins.scriptsecurity.sandbox.Whitelist;
import org.jenkinsci.plugins.scriptsecurity.sandbox.groovy.GroovySandbox;
import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.ProxyWhitelist;
import org.jenkinsci.plugins.scriptsecurity.scripts.ApprovalContext;
import org.jenkinsci.plugins.scriptsecurity.scripts.ScriptApproval;
import org.jenkinsci.plugins.scriptsecurity.scripts.languages.GroovyLanguage;
import org.jenkinsci.plugins.workflow.steps.StepContext;

import java.lang.ref.Reference;
import java.lang.ref.SoftReference;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
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

    private static final Map<String,Reference<Template>> templateCache = new HashMap<>();


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

        String text = "";
        if (isNotBlank(step.getFile())) {
            FilePath f = ws.child(step.getFile());
            if (f.exists() && !f.isDirectory()) {
                try (InputStream is = f.read()) {
                    text = IOUtils.toString(is, StandardCharsets.UTF_8);
                }
            } else if (f.isDirectory()) {
                throw new IllegalArgumentException(Messages.SimpleTemplateEngineStepExecution_fileIsDirectory(f.getRemote()));
            } else if (!f.exists()) {
                throw new FileNotFoundException(Messages.SimpleTemplateEngineStepExecution_fileNotFound(f.getRemote()));
	        }
        } else if (isNotBlank(step.getText())) {
            text = step.getText().trim();
        }

        final Map<String, Object> bindings = step.getBindings();

        String renderedTemplate = "";
        try {
            SimpleTemplateEngineStep.DescriptorImpl descriptor = Jenkins.get().getDescriptorByType(SimpleTemplateEngineStep.DescriptorImpl.class);
            GroovyShell shell = createEngine(descriptor, Collections.emptyMap(), false);
            SimpleTemplateEngine engine = new SimpleTemplateEngine(shell);
            Template tmpl;

            synchronized(templateCache) {
                Reference<Template> templateR = templateCache.get(text);
                tmpl = templateR == null ? null : templateR.get();
                if (tmpl == null) {
                    tmpl = engine.createTemplate(text);
                    templateCache.put(text, new SoftReference<>(tmpl));
                }
            }
            final Template templateFinal = tmpl;

            if (!step.isRunInSandbox()) {
                logger.println("simpleTemplateEngine running in script approval mode");
                ScriptApproval.get().configuring(text, GroovyLanguage.get(), ApprovalContext.create().withItem(getContext().get(Item.class)));
                renderedTemplate = templateFinal.make(bindings).toString();
/*
                if (ScriptApproval.get().isScriptApproved(text, GroovyLanguage.get())) {
                    renderedTemplate = templateFinal.make(bindings).toString();
                } else {
                    ScriptApproval.get().checking(text, GroovyLanguage.get());
                }
*/
            } else {
                logger.println("simpleTemplateEngine running in sandbox mode");
                renderedTemplate = GroovySandbox.runInSandbox(
                    () -> {
		    return templateFinal.make(bindings).toString();
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

    /**
     * Creates an engine (GroovyShell) to be used to execute Groovy code
     *
     * @param variables user variables to be added to the Groovy context
     * @return a GroovyShell instance
     */
    private GroovyShell createEngine(SimpleTemplateEngineStep.DescriptorImpl descriptor, Map<String, Object> variables, boolean secure) {

        ClassLoader cl;
        CompilerConfiguration cc;
        if (secure) {
            cl = GroovySandbox.createSecureClassLoader(Jenkins.get().getPluginManager().uberClassLoader);
            cc = GroovySandbox.createSecureCompilerConfiguration();
        } else {
            cl = Jenkins.get().getPluginManager().uberClassLoader;
            cc = new CompilerConfiguration();
        }
        cc.setScriptBaseClass(Script.class.getCanonicalName());
        Binding binding = new Binding();
        return new GroovyShell(cl, binding, cc);
    }

}

