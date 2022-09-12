/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2022 William Johnson
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

import hudson.model.Result;
import hudson.model.Item;
import static org.jenkinsci.plugins.pipeline.utility.steps.FilenameTestsUtils.separatorsToSystemEscaped;
import static org.jenkinsci.plugins.pipeline.utility.steps.Messages.AbstractFileOrTextStepDescriptorImpl_missingRequiredArgument;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.junit.rules.TemporaryFolder;
import jenkins.model.Jenkins;

/** Tests for {@link SimpleTemplateEngineStep}.
 *
 * @author William Johnson
 */
public class SimpleTemplateEngineStepTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void noArgument() throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                "node {\n" +
                        "def s = simpleTemplateEngine()\n" +
                        "}", true));
        WorkflowRun run = j.assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0).get());
        j.assertLogContains(AbstractFileOrTextStepDescriptorImpl_missingRequiredArgument("simpleTemplateEngine"), run);
    }

    @Test
    public void readFileAndText() throws Exception {
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                "node {\n" +
                        "def s = simpleTemplateEngine file:'doesnotexist.txt', text:'abc'\n" +
                        "}", true));
        WorkflowRun run = j.assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0).get());
        j.assertLogContains(Messages.SimpleTemplateEngineStepExecution_tooManyArguments("simpleTemplateEngine"), run);
    }

    @Test
    public void readFileIsDirectory() throws Exception {
        String tmp = temp.getRoot().getAbsolutePath();

        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                "node {\n" +
                        "def s = simpleTemplateEngine file: '" + separatorsToSystemEscaped(tmp) + "', bindings: [:]\n"+
                        "}", true));
        WorkflowRun run = j.assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0).get());
        j.assertLogContains(Messages.SimpleTemplateEngineStepExecution_fileIsDirectory(tmp), run);
    }

    @Test
    public void fileNotFound() throws Exception {
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                "node {\n" +
                        "def s = simpleTemplateEngine file: 'doesnotexist.txt' , bindings: [:]\n"+
                        "}", true));
        WorkflowRun run = j.assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0).get());
        j.assertLogContains(Messages.SimpleTemplateEngineStepExecution_fileNotFound("doesnotexist.txt"), run);
    }

    @Test
    public void missingBindingWithText() throws Exception {
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                "node {\n" +
                        "def s = simpleTemplateEngine text:'abc'\n"+
                        "}", true));
        WorkflowRun run = j.assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0).get());
        j.assertLogContains(Messages.SimpleTemplateEngineStepExecution_missingBindings("simpleTemplateEngine"), run);
    }

    @Test
    public void missingBindingWithFile() throws Exception {
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                "node {\n" +
                        "def s = simpleTemplateEngine file:'doesnotexist.txt'\n"+
                        "}", true));
        WorkflowRun run = j.assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0).get());
        j.assertLogContains(Messages.SimpleTemplateEngineStepExecution_missingBindings("simpleTemplateEngine"), run);
    }

    @Test
    public void noError() throws Exception {
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                "node() {\n"+
                        "def binding = [firstname : \"Grace\",]\n"+
                        "String text = '''Dear <%= firstname %> '''\n"+
                        "String result = simpleTemplateEngine text:text, bindings: binding\n"+
                        "assert result == 'Dear Grace'\n"+
                        "}", true));
        WorkflowRun run = j.assertBuildStatusSuccess(p.scheduleBuild2(0));
        j.assertLogContains("simpleTemplateEngine", run);
    }

    @Test
    public void incorrectTemplate() throws Exception {
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                "node() {\n"+
                        "def binding = [firstname : 'Grace',]\n"+
                        "String text = '''Dear <%= invalid %> '''\n"+
                        "String result = simpleTemplateEngine text:text, bindings: binding\n"+
                        "echo(result)\n"+
                "}", true));
        WorkflowRun run = j.assertBuildStatus(Result.SUCCESS, p.scheduleBuild2(0).get());
        j.assertLogContains("simpleTemplateEngine", run);
        j.assertLogContains("Exception raised during template rendering: No such property: invalid for class: SimpleTemplateScript", run);
    }

    // This test has a template that contains something that requires the sandbox
    @Test
    public void runInSandboxTrue() throws Exception {
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                "node() {\n"+
                    "def binding = [build: currentBuild]\n"+
                    "String text = '''<%\n"+
                    "    def f = hudson.model.Result.FAILURE\n"+ // This signature is pre-approved in the Script security sandbox
                    "%>'''\n"+
                    "String result = simpleTemplateEngine runInSandbox: true, text:text, bindings: binding\n"+
                "}", true));
        WorkflowRun run = j.assertBuildStatusSuccess(p.scheduleBuild2(0));
        System.out.println(j.getLog(run));
        j.assertLogContains("simpleTemplateEngine", run);
    }

    // TODO: The next test needs input stimulus that fails when runInSandbox is false.
    // We expect the test to fail and to tell the user to get approval
    @Test
    public void runInSandboxFalseButRequired() throws Exception {
        setupSecurity();
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                "node() {\n"+
                    "def binding = [build: currentBuild]\n"+
                    "String text = '''<%\n"+
                    "    def f = hudson.model.Result.FAILURE\n"+ // This signature is pre-approved in the Script security sandbox
                    "%>'''\n"+
                    "String result = simpleTemplateEngine runInSandbox: false, text:text, bindings: binding\n"+
                "}", true));
        WorkflowRun run = j.assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0).get());
        j.assertLogContains("simpleTemplateEngine", run);
    }

    // TODO: The next test needs input stimulus that fails when runInSandbox is not set
    // Same as above but checks that the runInSandbox default value is false
    @Test
    public void runInSandboxNotSpecifiedButRequired() throws Exception {
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                "node() {\n"+
                    "def binding = [build: currentBuild]\n"+
                    "String text = '''<%\n"+
                    "    def f = hudson.model.Result.FAILURE\n"+ // This signature is pre-approved in the Script security sandbox
                    "%>'''\n"+
                    "String result = simpleTemplateEngine text:text, bindings: binding\n"+
                "}", true));
        WorkflowRun run = j.assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0).get());
        j.assertLogContains("simpleTemplateEngine", run);
    }
    // TODO: a test that approves the script to be run so we can prove the script approval has worked

    private void setupSecurity() {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());

        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
            // otherwise we would need to create users for each email address tested, to bypass SECURITY-372 fix:
            .grant(Jenkins.READ, Item.READ).everywhere().toAuthenticated()
            // TODO I had plans for tests where bob would approve scripts written by alice
            .grant(Jenkins.ADMINISTER).everywhere().to("bob")
            .grant(Item.EXTENDED_READ).everywhere().to("alice")
        );
    }

}
