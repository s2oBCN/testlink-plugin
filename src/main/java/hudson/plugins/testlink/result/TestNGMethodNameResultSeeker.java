/*
 * The MIT License
 *
 * Copyright (c) <2011> <Bruno P. Kinoshita>
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
package hudson.plugins.testlink.result;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.AbstractBuild;
import hudson.plugins.testlink.TestLinkSite;
import hudson.plugins.testlink.util.JenkinsHelper;
import hudson.plugins.testlink.util.Messages;
import hudson.remoting.VirtualChannel;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;

import br.eti.kinoshita.testlinkjavaapi.constants.ExecutionStatus;

import com.tupilabs.testng.parser.Suite;
import com.tupilabs.testng.parser.Test;
import com.tupilabs.testng.parser.TestMethod;
import com.tupilabs.testng.parser.TestNGParser;

/**
 * <p>Seeks for test results matching each TestNG Method name with the key 
 * custom field.</p>
 * 
 * <p>Skips TestNG Method that were disabled.</p>
 * 
 * @author Bruno P. Kinoshita - http://www.kinoshita.eti.br
 * @since 3.1
 */
public class TestNGMethodNameResultSeeker extends AbstractTestNGResultSeeker {

	private static final long serialVersionUID = 3885800916930897675L;
	
	private final TestNGParser parser = new TestNGParser();
	
	
	/**
	 * @param includePattern
	 * @param keyCustomField
	 * @param KeywordExdFilter
	 * @param attachTestNGXML
	 * @param markSkippedTestAsBlocked
	 * @param includeNotes
	 */
	@DataBoundConstructor
	public TestNGMethodNameResultSeeker(String includePattern, String keyCustomField, String KeywordExdFilter, boolean attachTestNGXML,
			boolean attachPdfReport, String testCasesReportFolder, boolean markSkippedTestAsBlocked, boolean includeNotes) {
		super(includePattern, keyCustomField, KeywordExdFilter, attachTestNGXML,  attachPdfReport, testCasesReportFolder, markSkippedTestAsBlocked, includeNotes);
	}
	
	@Extension
	public static class DescriptorImpl extends ResultSeekerDescriptor {
		/*
		 * (non-Javadoc)
		 * 
		 * @see hudson.model.Descriptor#getDisplayName()
		 */
		@Override
		public String getDisplayName() {
			return "TestNG method name"; // TBD: i18n
		}
	}

	/* (non-Javadoc)
	 * @see hudson.plugins.testlink.result.ResultSeeker#seekAndUpdate(hudson.plugins.testlink.result.TestCaseWrapper<?>[], hudson.model.AbstractBuild, hudson.Launcher, hudson.model.BuildListener, hudson.plugins.testlink.TestLinkSite, hudson.plugins.testlink.result.Report)
	 */
	@Override
	public void seek(TestCaseWrapper[] automatedTestCases, AbstractBuild<?, ?> build, Launcher launcher, final BuildListener listener, TestLinkSite testlink) throws ResultSeekerException {
		listener.getLogger().println( Messages.Results_TestNG_LookingForTestMethod() );
		try {
			final String includePatternEnv = JenkinsHelper.expandVariable(build.getBuildVariableResolver(), build.getEnvironment(listener),includePattern);
			
			final List<Suite> suites = build.getWorkspace().act(new FilePath.FileCallable<List<Suite>>() {
				private static final long serialVersionUID = 1L;

				private List<Suite> suites = new ArrayList<Suite>();
				
				public List<Suite> invoke(File workspace, VirtualChannel channel)
						throws IOException, InterruptedException {
					
					final String[] xmls = TestNGMethodNameResultSeeker.this.scan(workspace, includePatternEnv, listener);
					
					for(String xml : xmls) {
						final File input = new File(workspace, xml);
						Suite suite = parser.parse(input);
						suites.add(suite);
					}
					
					return suites;
				}
			});
			
			ExecutorService executor = Executors.newFixedThreadPool(testlink.getParallelRequest());
			
			for(Suite suite : suites) {
				for(Test test : suite.getTests() ) {
					for(com.tupilabs.testng.parser.Class  clazz : test.getClasses()) {
						for(TestMethod method : clazz.getTestMethods()) {
							for(TestCaseWrapper automatedTestCase : automatedTestCases) {
								if (isInKeywordsFilter(automatedTestCase)) {
									haldleTestCase(executor, build, listener, testlink, suite, clazz, method, automatedTestCase);
								}								
							}
						}
					}
				}
			}
			
			executor.shutdown();
	        // Wait until all threads are finish
	        while (!executor.isTerminated()) {
	        }
	        
		} catch (IOException e) {
			throw new ResultSeekerException(e);
		} catch (InterruptedException e) {
			throw new ResultSeekerException(e);
		} 
	}



	/**
	 * @param build
	 * @param listener
	 * @param testlink
	 * @param suite
	 * @param clazz
	 * @param method
	 * @param automatedTestCase
	 */
	private void haldleTestCase(ExecutorService executor, final AbstractBuild<?, ?> build, final BuildListener listener,final TestLinkSite testlink,
			final Suite suite, final com.tupilabs.testng.parser.Class clazz, final TestMethod method, final  TestCaseWrapper automatedTestCase) {
		final String qualifiedName = clazz.getName()+'#'+method.getName();
		final String[] commaSeparatedValues = automatedTestCase.getKeyCustomFieldValues(this.keyCustomField);
		
		for(String value : commaSeparatedValues) {
			if(qualifiedName.equals(value)) {
				final ExecutionStatus status = this.getExecutionStatus(method);
				if(status != ExecutionStatus.NOT_RUN) {
					automatedTestCase.addCustomFieldAndStatus(value, status);
				}
				
				if(this.isIncludeNotes()) {
					final String notes = this.getTestNGNotes(method);
					automatedTestCase.appendNotes(notes);
				}
				
	            executor.execute(new Runnable(){
					public void run() {
						TestNGMethodNameResultSeeker.super.handleResult(automatedTestCase, build, listener, testlink, status, suite);
					}});
			}
		}		
	}

	/**
	 * @param suite
	 * @return
	 */
	private ExecutionStatus getExecutionStatus(TestMethod method) {
		if ( StringUtils.isNotBlank(method.getStatus()) ) {
			if(method.getStatus().equals(FAIL)) {
				return ExecutionStatus.FAILED; 
			} else if(method.getStatus().equals(SKIP)) {
				if(this.isMarkSkippedTestAsBlocked()) { 
					return ExecutionStatus.BLOCKED;
				} else {
					return ExecutionStatus.NOT_RUN;
				}
			}
		}
		return ExecutionStatus.PASSED;
	}

	/**
	 * Retrieves notes for TestNG suite.
	 * 
	 * @param method TestNG test method.
	 * @return notes for TestNG suite and test class.
	 */
	private String getTestNGNotes( TestMethod method )
	{
		StringBuilder notes = new StringBuilder();
		
		notes.append( 
				Messages.Results_TestNG_NotesForMethods(
						method.getName(), 
						method.getIsConfig(), 
						method.getSignature(), 
						method.getStatus(), 
						method.getDurationMs(), 
						method.getStartedAt(), 
						method.getFinishedAt()
				)
		);
		
		return notes.toString();
	}
	
}
