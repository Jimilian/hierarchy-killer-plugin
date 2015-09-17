/*
 * The MIT License
 * 
 * Copyright (c) 2015 Thorsten Möllers
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.jenkinsci.plugins.hierarchykiller;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

import static hudson.init.InitMilestone.PLUGINS_STARTED;
import hudson.Plugin;
import hudson.EnvVars;
import hudson.init.Initializer;
import hudson.model.TaskListener;
import hudson.model.Cause;
import hudson.model.Result;
import hudson.model.AbstractBuild;
import hudson.model.Executor;
import jenkins.model.Jenkins;

public class HierarchyKillerPlugin extends Plugin {
    private static final Logger LOGGER = Logger.getLogger(HierarchyKillerPlugin.class.getName());
    
    private static ConcurrentHashMap<AbstractBuild, RunData> jobMap;
    private static HierarchyKillerPlugin instance;
    private static int verbosity = 4;
    private static int hitCount = 0;
    private static final int debug=4;
    private static final int error=1;
    private static final int warning=2;
    private static final int info=3;
    private static final int info2=4;

    public static HierarchyKillerPlugin get() {
	return instance;
    }
    public synchronized static void notifyRunStarted(AbstractBuild run, TaskListener listener) {
	if (null == instance) {
	    log(listener, "HierarchyKillerPlugin.notifyRunStarted: Plugin not yet initialized");
	    return;
	}
	EnvVars env = getEnvVars(run, listener);
	if (!"true".equals(env.get("ENABLE_HIERARCHY_KILLER","false"))) {
	    log(debug, listener, "HierarchyKillerPlugin: ENABLE_HIERARCHY_KILLER not true, this build is not governed by HierarchyKiller");
	    return;
	}
	RunData r = new RunData();
	r.iListener = listener;
	jobMap.put(run, r);
	List<Cause> lCauses = run.getCauses();
	for(Cause c: lCauses) {
	    if (c instanceof Cause.UpstreamCause) {
		Cause.UpstreamCause usc = (Cause.UpstreamCause) c;
		if (usc.getUpstreamRun() instanceof AbstractBuild) {
		    r.iUpstream = (AbstractBuild) usc.getUpstreamRun();
		    RunData parentRunData = jobMap.get(r.iUpstream);
		    if (null != parentRunData) {
			// add current run to parents child-list (we know now that parent and child have hierarchy-killer enabled)
			log(jobMap.get(usc.getUpstreamRun()).iListener, "Triggered: " + env.get("JENKINS_URL")  + run.getUrl());
			parentRunData.iDownstream.add(run); 
		    }
		}
	    }
	}
	printStats(listener);
    }

    public synchronized static void notifyRunCompleted(AbstractBuild run, TaskListener listener) {
	if (null == instance ) {
	    log(listener, "notifyRunCompleted: Plugin not yet initialized");
	    return;
	}
	if (!jobMap.containsKey(run)) {
	    log(listener, "HierarchyKillerPlugin: notifyRunCompleted: This job is not governed by HierarchyKillerPlugin");
	    return;
	}
	RunData runData = jobMap.get(run); 
	if (null == runData) {
	    log(listener, "HierarchyKillerPlugin: notifyRunCompleted: No runData available to this run. This should never happen...");
	    return;
	}
	if (runData.iReason.length() > 0) {
	    log(listener, "Aborted by HierarchyKillerPlugin" + runData.iReason);
	}
	EnvVars env = getEnvVars(run, listener);
	if (!"true".equals(env.get("ENABLE_HIERARCHY_KILLER","false"))) {
	    log(listener, "notifyRunCompleted: ENABLE_HIERARCHY_KILLER not true, don't care about this build...");
	    jobMap.remove(run);
	    return;
	}
	Result result = run.getResult();
	if (null == result) {
	    log(listener, "notifyRunCompleted: result == null, ignore");
	    jobMap.remove(run);
	    return;
	}
	if ("true".equals(env.get("HIERARCHY_KILLER_KILL_UNSTABLE","false"))) {
	    if (result.isWorseThan(Result.SUCCESS)) {
		killUpAndDownstream(run, listener, env, runData);
	    }
	} else {
	    if (result.isWorseThan(Result.UNSTABLE)) {
		killUpAndDownstream(run, listener, env, runData);
	    }
	}
	jobMap.remove(run);
    }
    
    protected static void killUpAndDownstream(AbstractBuild run, TaskListener listener, EnvVars env, RunData runData) {
	String reason = ", caused by " + env.get("JENKINS_URL")  + run.getUrl() + runData.iReason;
	if ("true".equals(env.get("HIERARCHY_KILLER_KILL_UPSTREAM","false"))) {
	    if (null != runData.iUpstream && (runData.iUpstream.isBuilding())) {
		RunData upstreamRunData = jobMap.get(runData.iUpstream);
		kill(runData.iUpstream, upstreamRunData, reason);
	    } else {
		log(debug, listener, "killUpAndDownstream: upstream: no running upstream job found");
	    }
        }
	if ("true".equals(env.get("HIERARCHY_KILLER_KILL_DOWNSTREAM","false"))) {
	    killAllDownstream(run, listener, env, runData, reason);
        }
    }

    protected static void killAllDownstream(AbstractBuild run, TaskListener listener, EnvVars env, RunData runData, String reason) {
	/* Kill all downstream jobs */
	for(AbstractBuild r: runData.iDownstream) {
            if (!r.isBuilding()) {
		continue;
	    }
	    RunData downstreamRunData = jobMap.get(r);
	    if ( null == downstreamRunData ) {
		LOGGER.log(Level.SEVERE, "HierarchyKillerPlugin: Run is in downstreamlist of another run, not completed, but not in run list.");
		LOGGER.log(Level.SEVERE, "HierarchyKillerPlugin: This should not happen. Run is only added to downstream list when it is governed by this plugin");
		continue;
	    }
	    kill(r, downstreamRunData, reason);
	}
    }

    protected static void printStats(TaskListener listener) {
	if (verbosity > debug) {
	    LOGGER.log(Level.INFO, "HierarchyKillerPlugin: hitCount: " + hitCount + ", size of job-list: " + jobMap.size());
	}
    }

    protected static void kill(AbstractBuild run, RunData runData, String reason) {
	runData.iReason = reason; 
	run.setResult(Result.ABORTED);
	//As far as I know, all ongoing builds should implement the AbstractBuild interface; need to check for MatrixBuild
	LOGGER.log(Level.INFO, "HierarchyKillerPlugin: Aborted " + run.getUrl() + "(" + reason + ")");
	Executor e = run.getExecutor();
	e.interrupt(Result.ABORTED);
	hitCount++;
    }

    protected static EnvVars getEnvVars(AbstractBuild run, TaskListener listener) {
	EnvVars env = null;
	try {
	    env = run.getEnvironment(listener);
	} catch(IOException e) {
	    e.printStackTrace(listener.getLogger());
	} catch(InterruptedException e) {
	    e.printStackTrace(listener.getLogger());
	}
	if ( null == env ) {
	    env = new EnvVars();
	}
	return env;
    }

    public static void notifyRunFinalized(AbstractBuild run) {
	if (null == instance ) {
	    return;
	}
	jobMap.remove(run);
    }
    
    private static void log(int loglevel, final TaskListener listener, final String message) {
	if (loglevel < verbosity) {
	    log(listener, message);
	}
    }

    private static void log(final TaskListener listener, final String message) {
        listener.getLogger().println("HierarchyKillerPlugin: " + message);
    }

    @Initializer(after = PLUGINS_STARTED)
    public static void init() {
	LOGGER.log(Level.INFO, "HierarchyKillerPlugin: Initialized...");
	instance = Jenkins.getInstance().getPlugin(HierarchyKillerPlugin.class);
	jobMap = new ConcurrentHashMap<AbstractBuild, RunData>();
	if (null == instance) {
	    LOGGER.log(Level.INFO, "HierarchyKillerPlugin: Initialization failed...");
	}
    }
      
    @Override
    public void start() throws Exception {
	try {
	    load();
	    LOGGER.log(Level.INFO, "HierarchyKillerPlugin: Loaded...");
	} catch (IOException e) {
	    LOGGER.log(Level.SEVERE, "HierarchyKillerPlugin: Failed to load", e);
	}
    }
     
    private void start(Runnable runnable) {
	Executors.newSingleThreadExecutor().submit(runnable);
    }
	
    public static class RunData {
	public List<AbstractBuild > iDownstream;
	public AbstractBuild iUpstream;
	public TaskListener iListener;
	public String iReason;
	public RunData() {
	    iListener = null;
	    iDownstream = new Vector<AbstractBuild >();
	    iUpstream = null;
	    iReason = "";
	}
    }
}
