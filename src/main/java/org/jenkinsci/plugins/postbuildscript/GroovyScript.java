package org.jenkinsci.plugins.postbuildscript;

import hudson.Util;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * @author Gregory Boissinot
 */
public class GroovyScript {

    private final String filePath;

    private final String content;

    @DataBoundConstructor
    public GroovyScript(String filePath, String content) {
        this.filePath = Util.fixEmpty(filePath);
        this.content = Util.fixEmpty(content);
    }

    @SuppressWarnings("unused")
    public String getFilePath() {
        return filePath;
    }

    @SuppressWarnings("unused")
    public String getContent() {
        return content;
    }
}