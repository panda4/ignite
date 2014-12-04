/* @java.file.header */

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.session;

import org.apache.ignite.*;
import org.apache.ignite.compute.*;
import org.apache.ignite.resources.*;
import org.gridgain.grid.*;
import org.gridgain.grid.logger.*;
import org.gridgain.grid.util.typedef.*;
import org.gridgain.testframework.*;
import org.gridgain.testframework.junits.common.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 *
 */
@SuppressWarnings({"CatchGenericClass"})
@GridCommonTest(group = "Task Session")
public class GridSessionFutureWaitJobAttributeSelfTest extends GridCommonAbstractTest {
    /** */
    private static final int WAIT_TIME = 20000;

    /** */
    public static final int SPLIT_COUNT = 5;

    /** */
    public static final int EXEC_COUNT = 25;

    /** */
    private static CountDownLatch[] startSignal;

    /** */
    private static CountDownLatch[] stopSignal;

    /** */
    public GridSessionFutureWaitJobAttributeSelfTest() {
        super(true);
    }

    /**
     * @throws Exception if failed.
     */
    public void testSetAttribute() throws Exception {
        Ignite ignite = G.grid(getTestGridName());

        ignite.compute().localDeployTask(GridTaskSessionTestTask.class, GridTaskSessionTestTask.class.getClassLoader());

        refreshInitialData();

        for (int i = 0; i < EXEC_COUNT; i++)
            checkTask(i);
    }

    /**
     * @throws Exception if failed.
     */
    public void testMultiThreaded() throws Exception {
        Ignite ignite = G.grid(getTestGridName());

        ignite.compute().localDeployTask(GridTaskSessionTestTask.class, GridTaskSessionTestTask.class.getClassLoader());

        refreshInitialData();

        final GridThreadSerialNumber sNum = new GridThreadSerialNumber();

        final AtomicBoolean failed = new AtomicBoolean(false);

        GridTestUtils.runMultiThreaded(new Runnable() {
            @Override public void run() {
                int num = sNum.get();

                try {
                    checkTask(num);
                }
                catch (Throwable e) {
                    error("Failed to execute task.", e);

                    failed.set(true);
                }
            }
        }, EXEC_COUNT, "grid-session-test");

        if (failed.get())
            fail();
    }

    /**
     * @param num Number.
     * @throws InterruptedException if failed.
     * @throws GridException if failed.
     */
    private void checkTask(int num) throws InterruptedException, GridException {
        Ignite ignite = G.grid(getTestGridName());

        IgniteCompute comp = ignite.compute().enableAsync();

        comp.execute(GridTaskSessionTestTask.class.getName(), num);

        ComputeTaskFuture<?> fut = comp.future();

        assert fut != null;

        try {
            // Wait until task receive results from jobs.
            boolean await = startSignal[num].await(WAIT_TIME, TimeUnit.MILLISECONDS);

            assert await : "Jobs did not executed.";

            String val = fut.getTaskSession().waitForAttribute("testName", 100000);

            info("Received attribute 'testName': " + val);

            // Signal jobs to finish work.
            stopSignal[num].countDown();

            assert "testVal".equals(val) : "Invalid attribute value: " + val;

            Object res = fut.get();

            assert (Integer)res == SPLIT_COUNT : "Invalid result [num=" + num + ", fut=" + fut + ']';
        }
        finally {
            // We must wait for the jobs to be sure that they have completed
            // their execution since they use static variable (shared for the tests).
            fut.get();
        }
    }

    /** */
    private void refreshInitialData() {
        startSignal = new CountDownLatch[EXEC_COUNT];
        stopSignal = new CountDownLatch[EXEC_COUNT];

        for(int i=0 ; i < EXEC_COUNT; i++){
            startSignal[i] = new CountDownLatch(1);

            stopSignal[i] = new CountDownLatch(1);
        }
    }

    /**
     *
     */
    @ComputeTaskSessionFullSupport
    private static class GridTaskSessionTestTask extends ComputeTaskSplitAdapter<Serializable, Integer> {
        /** */
        @GridLoggerResource
        private GridLogger log;

        /** */
        @GridTaskSessionResource
        private ComputeTaskSession taskSes;

        /** */
        private volatile int taskNum = -1;

        /** {@inheritDoc} */
        @Override protected Collection<? extends ComputeJob> split(int gridSize, Serializable arg) throws GridException {
            if (log.isInfoEnabled())
                log.info("Splitting job [task=" + this + ", gridSize=" + gridSize + ", arg=" + arg + ']');

            assert arg != null;

            taskNum = (Integer)arg;

            assert taskNum != -1;

            Collection<ComputeJob> jobs = new ArrayList<>(SPLIT_COUNT);

            for (int i = 1; i <= SPLIT_COUNT; i++) {
                jobs.add(new ComputeJobAdapter(i) {
                    @Override public Serializable execute() throws GridException {
                        assert taskSes != null;

                        if (log.isInfoEnabled()) {
                            log.info("Computing job [job=" + this + ", arg=" + argument(0) + ']');

                            log.info("Set attribute 'testName'.");
                        }

                        taskSes.setAttribute("testName", "testVal");

                        // Signal main process to wait for attribute.
                        startSignal[taskNum].countDown();

                        try {
                            // Wait until future receive attribute.
                            if (!stopSignal[taskNum].await(WAIT_TIME, TimeUnit.MILLISECONDS))
                                fail();
                        }
                        catch (InterruptedException e) {
                            if (log.isInfoEnabled())
                                log.info("Job got interrupted [arg=" + argument(0) + ", e=" + e + ']');

                            return 0;
                        }

                        return 1;
                    }
                });
            }

            return jobs;
        }

        /** {@inheritDoc} */
        @Override public ComputeJobResultPolicy result(ComputeJobResult res, List<ComputeJobResult> received)
            throws GridException {
            if (res.getException() != null)
                throw res.getException();

            return received.size() == SPLIT_COUNT ? ComputeJobResultPolicy.REDUCE : ComputeJobResultPolicy.WAIT;
        }

        /** {@inheritDoc} */
        @Override public Integer reduce(List<ComputeJobResult> results) throws GridException {
            if (log.isInfoEnabled())
                log.info("Reducing job [job=" + this + ", results=" + results + ']');

            if (results.size() < SPLIT_COUNT)
                fail();

            int sum = 0;

            for (ComputeJobResult result : results) {
                if (result.getData() != null)
                    sum += (Integer)result.getData();
            }

            return sum;
        }
    }
}
