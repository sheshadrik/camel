/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.itest.springboot.command;

import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.camel.CamelContext;
import org.apache.camel.itest.springboot.Command;
import org.apache.camel.itest.springboot.ITestConfig;
import org.junit.Assert;
import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.RegexPatternTypeFilter;
import org.springframework.stereotype.Component;

/**
 * A command that executes all unit tests contained in the module.
 */
@Component("unittest")
public class UnitTestCommand extends AbstractTestCommand implements Command {

    Logger logger = LoggerFactory.getLogger(getClass());

    @Autowired
    private CamelContext context;

    @Override
    public UnitTestResult executeTest(ITestConfig config, String component) throws Exception {

        logger.info("Spring-Boot test configuration {}", config);

        Pattern pattern = Pattern.compile(config.getUnitTestInclusionPattern());

        logger.info("Scaning the classpath for test classes");
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);

        scanner.addIncludeFilter(new RegexPatternTypeFilter(pattern));

        List<String> testClasses = new LinkedList<>(scanner.findCandidateComponents(config.getUnitTestBasePackage()).stream().map(bd -> bd.getBeanClassName()).collect(Collectors.toList()));

        if (config.getUnitTestExclusionPattern() != null) {
            Pattern exclusionPattern = Pattern.compile(config.getUnitTestExclusionPattern());
            for (Iterator<String> it = testClasses.iterator(); it.hasNext();) {
                String cn = it.next();
                if (exclusionPattern.matcher(cn).matches()) {
                    logger.warn("Excluding Test Class: {}", cn);
                    it.remove();
                }
            }
        }

        List<Class<?>> classes = new ArrayList<>();
        for (String cn : testClasses) {
            Class<?> clazz = Class.forName(cn);
            if (isAdmissible(clazz)) {
                logger.info("Found admissible test class: {}", cn);
                classes.add(clazz);
            }
        }


        Result result = JUnitCore.runClasses(classes.toArray(new Class[]{}));
        logger.info("Run JUnit tests on {} test classes", classes.size());
        logger.info("Success: " + result.wasSuccessful() + " - Test Run: " + result.getRunCount() + " - Failures: " + result.getFailureCount() + " - Ignored Tests: " + result.getIgnoreCount());

        for (Failure f : result.getFailures()) {
            logger.warn("Failed test description: {}", f.getDescription());
            logger.warn("Message: {}", f.getMessage());
            if (f.getException() != null) {
                logger.warn("Exception thrown from test", f.getException());
            }
        }

        if (!result.wasSuccessful()) {
            Assert.fail("Some unit tests failed (" + result.getFailureCount() + "/" + result.getRunCount() + "), check the logs for more details");
        }

        if (result.getRunCount() == 0 && config.getUnitTestsExpectedNumber() == null) {
            Assert.fail("No tests have been found");
        }

        Integer expectedTests = config.getUnitTestsExpectedNumber();
        if (expectedTests != null && expectedTests != result.getRunCount()) {
            Assert.fail("Wrong number of tests: expected " + expectedTests + " found " + result.getRunCount());
        }

        return new UnitTestResult(result);
    }

    private boolean isAdmissible(Class<?> testClass) {

        if (testClass.getPackage().getName().startsWith("org.apache.camel.itest.springboot")) {
            // no tests from the integration test suite
            return false;
        }

        URL location = testClass.getResource("/" + testClass.getName().replace(".", "/") + ".class");
        if (location != null) {
            int firstLevel = location.toString().indexOf("!/");
            int lastLevel = location.toString().lastIndexOf("!/");
            if (firstLevel >= 0 && lastLevel >= 0 && firstLevel != lastLevel) {
                // test class is in a nested jar, skipping
                return false;
            }
        }

        return true;
    }

}
