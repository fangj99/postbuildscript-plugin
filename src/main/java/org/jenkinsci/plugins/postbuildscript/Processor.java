package org.jenkinsci.plugins.postbuildscript;

import com.google.common.base.Strings;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.tasks.BuildStep;
import org.jenkinsci.plugins.postbuildscript.model.Configuration;
import org.jenkinsci.plugins.postbuildscript.model.PostBuildItem;
import org.jenkinsci.plugins.postbuildscript.model.PostBuildStep;
import org.jenkinsci.plugins.postbuildscript.model.Role;
import org.jenkinsci.plugins.postbuildscript.model.Script;
import org.jenkinsci.plugins.postbuildscript.model.ScriptFile;
import org.jenkinsci.plugins.postbuildscript.model.ScriptType;
import org.jenkinsci.plugins.postbuildscript.service.Command;
import org.jenkinsci.plugins.postbuildscript.service.CommandExecutor;
import org.jenkinsci.plugins.postbuildscript.service.GroovyScriptExecutorFactory;
import org.jenkinsci.plugins.postbuildscript.service.GroovyScriptPreparer;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;

public class Processor {

    private final AbstractBuild<?, ?> build;
    private final Launcher launcher;
    private final BuildListener listener;
    private final Configuration config;
    private final Logger logger;

    public Processor(
        AbstractBuild<?, ?> build,
        Launcher launcher,
        BuildListener listener,
        Configuration config) {
        this.build = build;
        Result result = build.getResult();
        if (result == null) {
            this.launcher = launcher;
        } else {
            this.launcher = launcher.decorateByEnv(
                new EnvVars("BUILD_RESULT", result.toString())); //NON-NLS
        }
        this.listener = listener;
        this.config = config;
        logger = new Logger(listener);
    }

    private Command getResolvedCommand(String command) throws PostBuildScriptException {
        if (command == null) {
            return null;
        }

        try {
            String resolvedPath = Util.replaceMacro(command, build.getEnvironment(listener));
            resolvedPath = Util.replaceMacro(resolvedPath, build.getBuildVariables());
            return new Command(resolvedPath);
        } catch (IOException | InterruptedException ioe) {
            throw new PostBuildScriptException(ioe);
        }
    }

    public boolean process() {
        logger.info(Messages.PostBuildScript_ExecutingPostBuildScripts());
        try {
            return processScripts();
        } catch (PostBuildScriptException pse) {
            logger.error(Messages.PostBuildScript_ProblemOccured(pse.getMessage()));
            failOrUnstable();
            return false;
        }
    }

    private boolean processScripts() throws PostBuildScriptException {

        if (!processScriptFiles()) {
            return failOrUnstable();
        }

        if (!processGroovyScripts()) {
            return failOrUnstable();
        }

        return processBuildSteps() || failOrUnstable();

    }

    private boolean failOrUnstable() {
        if (config.isMarkBuildUnstable()) {
            build.setResult(Result.UNSTABLE);
            return true;
        }
        build.setResult(Result.FAILURE);
        return false;
    }

    private boolean processScriptFiles() throws PostBuildScriptException {

        Optional<Result> result = Optional.ofNullable(build.getResult());
        FilePath workspace = build.getWorkspace();
        CommandExecutor commandExecutor = new CommandExecutor(logger, listener, workspace, launcher);
        GroovyScriptPreparer scriptPreparer = createGroovyScriptPreparer();
        for (ScriptFile scriptFile : config.getScriptFiles()) {
            String filePath = scriptFile.getFilePath();
            if (Strings.nullToEmpty(filePath).trim().isEmpty()) {
                logger.error(Messages.PostBuildScript_NoFilePathProvided(config.scriptFileIndexOf(scriptFile)));
                continue;
            }

            if (!roleFits(scriptFile)) {
                logRoleDoesNotMatch(scriptFile.getRole(), filePath);
                continue;
            }

            if (!result.isPresent() || !scriptFile.shouldBeExecuted(result.get().toString())) {
                logResultDoesNotMatch(scriptFile.getResults(), filePath);
                continue;
            }

            Command command = getResolvedCommand(filePath);
            if (command != null) {

                if (scriptFile.getScriptType() == ScriptType.GENERIC) {
                    int returnCode = commandExecutor.executeCommand(command);
                    if (returnCode != 0) {
                        return false;
                    }
                } else {
                    if (!scriptPreparer.evaluateCommand(command)) {
                        return false;
                    }
                }
            }

        }
        return true;
    }

    private boolean processGroovyScripts() {

        Optional<Result> result = Optional.ofNullable(build.getResult());
        GroovyScriptPreparer executor = createGroovyScriptPreparer();
        for (Script script : config.getGroovyScripts()) {

            if (!roleFits(script)) {
                logRoleDoesNotMatch(script.getRole(), Messages.PostBuildScript_GroovyScript(
                    config.groovyScriptIndexOf(script)));
                continue;
            }

            if (!result.isPresent() || !script.shouldBeExecuted(result.get().toString())) {
                logResultDoesNotMatch(script.getResults(), Messages.PostBuildScript_GroovyScript(
                    config.groovyScriptIndexOf(script)));
                continue;
            }

            String content = script.getContent();
            if (content != null) {
                if (!executor.evaluateScript(content)) {
                    return false;
                }
            }

        }
        return true;
    }

    private GroovyScriptPreparer createGroovyScriptPreparer() {
        FilePath workspace = build.getWorkspace();
        GroovyScriptExecutorFactory groovyScriptExecutorFactory =
            new GroovyScriptExecutorFactory(build, logger);
        return new GroovyScriptPreparer(logger, workspace, groovyScriptExecutorFactory);
    }

    private boolean processBuildSteps() throws PostBuildScriptException {

        Optional<Result> result = Optional.ofNullable(build.getResult());
        try {
            for (PostBuildStep postBuildStep : config.getBuildSteps()) {

                if (!roleFits(postBuildStep)) {
                    logRoleDoesNotMatch(postBuildStep.getRole(), Messages.PostBuildScript_BuildStep(
                        config.buildStepIndexOf(postBuildStep)));
                    continue;
                }

                if (!result.isPresent() || !postBuildStep.shouldBeExecuted(result.get().toString())) {
                    logResultDoesNotMatch(postBuildStep.getResults(), Messages.PostBuildScript_BuildStep(
                        config.buildStepIndexOf(postBuildStep)));
                    continue;
                }

                for (BuildStep buildStep : postBuildStep.getBuildSteps()) {
                    if (!buildStep.perform(build, launcher, listener)) {
                        return false;
                    }
                }

            }
            return true;
        } catch (IOException | InterruptedException ioe) {
            throw new PostBuildScriptException(ioe);
        }
    }

    private boolean roleFits(PostBuildItem item) {
        boolean runsOnMaster = build.getBuiltOnStr() == null || build.getBuiltOnStr().isEmpty();
        if (runsOnMaster) {
            return item.shouldRunOnMaster();
        }
        return item.shouldRunOnSlave();
    }

    private void logResultDoesNotMatch(Set<String> results, String scriptName) {
        logger.info(Messages.PostBuildScript_BuildDoesNotFit(results, scriptName));
    }

    private void logRoleDoesNotMatch(Role role, String scriptName) {
        logger.info(Messages.PostBuildScript_NodeDoesNotHaveRole(role, scriptName));
    }

}
