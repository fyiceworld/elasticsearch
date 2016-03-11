/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.index.reindex;

import org.elasticsearch.Version;
import org.elasticsearch.action.Action;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionRequestBuilder;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.ActionWriteResponse;
import org.elasticsearch.action.admin.indices.refresh.RefreshRequest;
import org.elasticsearch.action.bulk.BackoffPolicy;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkItemResponse.Failure;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.delete.DeleteResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.search.ClearScrollRequest;
import org.elasticsearch.action.search.ClearScrollResponse;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchScrollRequest;
import org.elasticsearch.action.search.ShardSearchFailure;
import org.elasticsearch.action.support.PlainActionFuture;
import org.elasticsearch.action.support.replication.ReplicationRequest;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.action.update.UpdateResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.FilterClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.text.Text;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.util.Consumer;
import org.elasticsearch.common.util.concurrent.AbstractRunnable;
import org.elasticsearch.common.util.concurrent.EsRejectedExecutionException;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.engine.VersionConflictEngineException;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.rest.NoOpClient;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHitField;
import org.elasticsearch.search.internal.InternalSearchHit;
import org.elasticsearch.search.internal.InternalSearchHits;
import org.elasticsearch.search.internal.InternalSearchResponse;
import org.elasticsearch.tasks.TaskManager;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.threadpool.ThreadPool;
import org.junit.After;
import org.junit.Before;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Collections.singleton;
import static org.apache.lucene.util.TestUtil.randomSimpleString;
import static org.elasticsearch.action.bulk.BackoffPolicy.constantBackoff;
import static org.elasticsearch.common.unit.TimeValue.parseTimeValue;
import static org.elasticsearch.common.unit.TimeValue.timeValueMillis;
import static org.elasticsearch.common.unit.TimeValue.timeValueMinutes;
import static org.elasticsearch.common.unit.TimeValue.timeValueSeconds;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.either;
import static org.hamcrest.Matchers.emptyCollectionOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;

public class AsyncBulkByScrollActionTests extends ESTestCase {
    private MyMockClient client;
    private ThreadPool threadPool;
    private Version smallestNonClientVersion;
    private DummyAbstractBulkByScrollRequest mainRequest;
    private SearchRequest firstSearchRequest;
    private PlainActionFuture<BulkIndexByScrollResponse> listener;
    private String scrollId;
    private TaskManager taskManager;
    private BulkByScrollTask task;

    @Before
    public void setupForTest() {
        client = new MyMockClient(new NoOpClient(getTestName()));
        threadPool = new ThreadPool(getTestName());
        smallestNonClientVersion = Version.V_2_3_0;
        mainRequest = new DummyAbstractBulkByScrollRequest();
        firstSearchRequest = new SearchRequest().scroll(timeValueSeconds(10));
        listener = new PlainActionFuture<>();
        scrollId = null;
        taskManager = new TaskManager(Settings.EMPTY);
        task = (BulkByScrollTask) taskManager.register("don'tcare", "hereeither", mainRequest);
        mainRequest.putHeader(randomSimpleString(random()), randomSimpleString(random()));
        mainRequest.putInContext(new Object(), new Object());
    }

    @After
    public void tearDownAndVerifyCommonStuff() {
        client.close();
        threadPool.shutdown();
    }

    /**
     * Generates a random scrollId and registers it so that when the test
     * finishes we check that it was cleared. Subsequent calls reregister a new
     * random scroll id so it is checked instead.
     */
    private String scrollId() {
        scrollId = randomSimpleString(random(), 1, 1000); // Empty string's get special behavior we don't want
        return scrollId;
    }

    public void testFirstSearchRequestHasContextFromMainRequest() {
        firstSearchRequest = new SearchRequest();
        new DummyAbstractAsyncBulkByScrollAction().start();
        // Actual assertions done by the client instance
    }

    public void testScrollResponseSetsTotal() {
        // Default is 0, meaning unstarted
        assertEquals(0, task.getStatus().getTotal());

        long total = randomIntBetween(0, Integer.MAX_VALUE);
        InternalSearchHits hits = new InternalSearchHits(null, total, 0);
        InternalSearchResponse searchResponse = new InternalSearchResponse(hits, null, null, null, false, false);
        new DummyAbstractAsyncBulkByScrollAction().onScrollResponse(timeValueSeconds(0),
                new SearchResponse(searchResponse, scrollId(), 5, 4, randomLong(), null));
        assertEquals(total, task.getStatus().getTotal());
    }

    public void testSubsequentSearchScrollRequestsHaveContextFromMainRequest() {
        firstSearchRequest = new SearchRequest().scroll("1s");
        DummyAbstractAsyncBulkByScrollAction action = new DummyAbstractAsyncBulkByScrollAction();
        action.setScroll(scrollId());
        action.startNextScroll(0);
        // Actual assertions done by the client instance
    }

    /**
     * Tests that each scroll response is a batch and that the batch is launched properly.
     */
    public void testScrollResponseBatchingBehavior() throws Exception {
        // Replace the generic thread pool with one that executes immediately so the batch is updated immediately
        threadPool.shutdown();
        threadPool = new ThreadPool(getTestName()) {
            @Override
            public Executor generic() {
                return new Executor() {
                    @Override
                    public void execute(Runnable command) {
                        command.run();
                    }
                };
            }
        };
        int maxBatches = randomIntBetween(0, 100);
        for (int batches = 1; batches < maxBatches; batches++) {
            long now = System.nanoTime();
            InternalSearchHit hit = new InternalSearchHit(0, "id", new Text("type"), Collections.<String, SearchHitField>emptyMap());
            InternalSearchHits hits = new InternalSearchHits(new InternalSearchHit[] { hit }, 0, 0);
            InternalSearchResponse searchResponse = new InternalSearchResponse(hits, null, null, null, false, false);
            DummyAbstractAsyncBulkByScrollAction action = new DummyAbstractAsyncBulkByScrollAction();
            action.onScrollResponse(timeValueSeconds(0),
                    new SearchResponse(searchResponse, scrollId(), 5, 4, randomLong(), null));

            // Use assert busy because the update happens on another thread
            final int expectedBatches = batches;
            assertBusy(new Runnable() {
                @Override
                public void run() {
                    assertEquals(expectedBatches, task.getStatus().getBatches());
                }
            });

            /*
             * While we're here we can check that getting a scroll response sets the last scroll start time which makes sure the wait time
             * isn't counted as time that the last batch took.
             */
            assertThat(action.getLastBatchStartTime(), greaterThanOrEqualTo(now));
        }
    }

    public void testBulkResponseSetsLotsOfStatus() {
        mainRequest.setAbortOnVersionConflict(false);
        int maxBatches = randomIntBetween(0, 100);
        long versionConflicts = 0;
        long created = 0;
        long updated = 0;
        long deleted = 0;
        for (int batches = 0; batches < maxBatches; batches++) {
            BulkItemResponse[] responses = new BulkItemResponse[randomIntBetween(0, 100)];
            for (int i = 0; i < responses.length; i++) {
                ShardId shardId = new ShardId(new Index("name"), 0);
                String opType;
                if (rarely()) {
                    opType = randomSimpleString(random());
                    versionConflicts++;
                    responses[i] = new BulkItemResponse(i, opType, new Failure(shardId.getIndex(), "type", "id" + i,
                            new VersionConflictEngineException(shardId, "type", "id", 12, 1234)));
                    continue;
                }
                boolean createdResponse;
                switch (randomIntBetween(0, 2)) {
                case 0:
                    opType = randomFrom("index", "create");
                    createdResponse = true;
                    created++;
                    break;
                case 1:
                    opType = randomFrom("index", "create");
                    createdResponse = false;
                    updated++;
                    break;
                case 2:
                    opType = "delete";
                    createdResponse = false;
                    deleted++;
                    break;
                default:
                    throw new RuntimeException("Bad scenario");
                }
                responses[i] = new BulkItemResponse(i, opType,
                        new IndexResponse(shardId.getIndex(), "type", "id" + i, randomInt(), createdResponse));
            }
            new DummyAbstractAsyncBulkByScrollAction().onBulkResponse(new BulkResponse(responses, 0));
            assertEquals(versionConflicts, task.getStatus().getVersionConflicts());
            assertEquals(updated, task.getStatus().getUpdated());
            assertEquals(created, task.getStatus().getCreated());
            assertEquals(deleted, task.getStatus().getDeleted());
            assertEquals(versionConflicts, task.getStatus().getVersionConflicts());
        }
    }

    /**
     * Mimicks a ThreadPool rejecting execution of the task.
     */
    public void testThreadPoolRejectionsAbortRequest() throws Exception {
        final TimeValue expectedDelay = parseTimeValue(randomPositiveTimeValue(), null, "test");
        threadPool.shutdown();
        threadPool = new ThreadPool(getTestName()) {
            @Override
            public ScheduledFuture<?> schedule(TimeValue delay, String name, Runnable command) {
                assertEquals(expectedDelay, delay); // While we're here we can check that the sleep made it through
                ((AbstractRunnable) command).onRejection(new EsRejectedExecutionException("test"));
                return null;
            }
        };
        InternalSearchHits hits = new InternalSearchHits(null, 0, 0);
        InternalSearchResponse searchResponse = new InternalSearchResponse(hits, null, null, null, false, false);
        new DummyAbstractAsyncBulkByScrollAction()
                .onScrollResponse(expectedDelay, new SearchResponse(searchResponse, scrollId(), 5, 4, randomLong(), null));
        try {
            listener.get();
            fail("Expected a failure");
        } catch (ExecutionException e) {
            assertThat(e.getMessage(), equalTo("EsRejectedExecutionException[test]"));
        }
        assertThat(client.scrollsCleared, contains(scrollId));

        // When the task is rejected we don't increment the throttled timer
        assertEquals(timeValueMillis(0), task.getStatus().getThrottled());
    }

    /**
     * Mimicks shard search failures usually caused by the data node serving the
     * scroll request going down.
     */
    public void testShardFailuresAbortRequest() throws Exception {
        ShardSearchFailure shardFailure = new ShardSearchFailure(new RuntimeException("test"));
        InternalSearchResponse internalResponse = new InternalSearchResponse(null, null, null, null, false, null);
        new DummyAbstractAsyncBulkByScrollAction().onScrollResponse(timeValueSeconds(0),
                new SearchResponse(internalResponse, scrollId(), 5, 4, randomLong(), new ShardSearchFailure[] { shardFailure }));
        BulkIndexByScrollResponse response = listener.get();
        assertThat(response.getIndexingFailures(), emptyCollectionOf(Failure.class));
        assertThat(response.getSearchFailures(), contains(shardFailure));
        assertFalse(response.isTimedOut());
        assertNull(response.getReasonCancelled());
        assertThat(client.scrollsCleared, contains(scrollId));
    }

    /**
     * Mimicks search timeouts.
     */
    public void testSearchTimeoutsAbortRequest() throws Exception {
        InternalSearchResponse internalResponse = new InternalSearchResponse(null, null, null, null, true, null);
        new DummyAbstractAsyncBulkByScrollAction().onScrollResponse(timeValueSeconds(0),
                new SearchResponse(internalResponse, scrollId(), 5, 4, randomLong(), new ShardSearchFailure[0]));
        BulkIndexByScrollResponse response = listener.get();
        assertThat(response.getIndexingFailures(), emptyCollectionOf(Failure.class));
        assertThat(response.getSearchFailures(), emptyCollectionOf(ShardSearchFailure.class));
        assertTrue(response.isTimedOut());
        assertNull(response.getReasonCancelled());
        assertThat(client.scrollsCleared, contains(scrollId));
    }


    /**
     * Mimicks bulk indexing failures.
     */
    public void testBulkFailuresAbortRequest() throws Exception {
        Failure failure = new Failure("index", "type", "id", new RuntimeException("test"));
        DummyAbstractAsyncBulkByScrollAction action = new DummyAbstractAsyncBulkByScrollAction();
        action.onBulkResponse(new BulkResponse(new BulkItemResponse[] {new BulkItemResponse(0, "index", failure)}, randomLong()));
        BulkIndexByScrollResponse response = listener.get();
        assertThat(response.getIndexingFailures(), contains(failure));
        assertThat(response.getSearchFailures(), emptyCollectionOf(ShardSearchFailure.class));
        assertNull(response.getReasonCancelled());
    }

    /**
     * Mimicks script failures or general wrongness by implementers.
     */
    public void testListenerReceiveBuildBulkExceptions() throws Exception {
        DummyAbstractAsyncBulkByScrollAction action = new DummyAbstractAsyncBulkByScrollAction() {
            @Override
            protected BulkRequest buildBulk(Iterable<SearchHit> docs) {
                throw new RuntimeException("surprise");
            }
        };
        InternalSearchHit hit = new InternalSearchHit(0, "id", new Text("type"), Collections.<String, SearchHitField>emptyMap());
        InternalSearchHits hits = new InternalSearchHits(new InternalSearchHit[] {hit}, 0, 0);
        InternalSearchResponse searchResponse = new InternalSearchResponse(hits, null, null, null, false, false);
        action.onScrollResponse(timeValueSeconds(0), new SearchResponse(searchResponse, scrollId(), 5, 4, randomLong(), null));
        try {
            listener.get();
            fail("Expected failure.");
        } catch (ExecutionException e) {
            assertThat(e.getCause(), instanceOf(RuntimeException.class));
            assertThat(e.getCause().getMessage(), equalTo("surprise"));
        }
    }

    /**
     * Mimicks bulk rejections. These should be retried and eventually succeed.
     */
    public void testBulkRejectionsRetryWithEnoughRetries() throws Exception {
        int bulksToTry = randomIntBetween(1, 10);
        long retryAttempts = 0;
        for (int i = 0; i < bulksToTry; i++) {
            retryAttempts += retryTestCase(false);
            assertEquals(retryAttempts, task.getStatus().getRetries());
        }
    }

    /**
     * Mimicks bulk rejections. These should be retried but we fail anyway because we run out of retries.
     */
    public void testBulkRejectionsRetryAndFailAnyway() throws Exception {
        long retryAttempts = retryTestCase(true);
        assertEquals(retryAttempts, task.getStatus().getRetries());
    }

    public void testPerfectlyThrottledBatchTime() {
        DummyAbstractAsyncBulkByScrollAction action = new DummyAbstractAsyncBulkByScrollAction();
        mainRequest.setRequestsPerSecond(0);
        assertThat((double) action.perfectlyThrottledBatchTime(randomInt()), closeTo(0f, 0f));

        int total = between(0, 1000000);
        task.rethrottle(1);
        assertThat((double) action.perfectlyThrottledBatchTime(total),
                closeTo(TimeUnit.SECONDS.toNanos(total), TimeUnit.SECONDS.toNanos(1)));
    }

    public void testScrollDelay() throws Exception {
        /*
         * Replace the thread pool with one that will save the delay sent for the command. We'll use that to check that we used a proper
         * delay for throttling.
         */
        final AtomicReference<TimeValue> capturedDelay = new AtomicReference<>();
        final AtomicReference<Runnable> capturedCommand = new AtomicReference<>();
        threadPool.shutdown();
        threadPool = new ThreadPool(getTestName()) {
            @Override
            public ScheduledFuture<?> schedule(TimeValue delay, String name, Runnable command) {
                capturedDelay.set(delay);
                capturedCommand.set(command);
                return null;
            }
        };

        DummyAbstractAsyncBulkByScrollAction action = new DummyAbstractAsyncBulkByScrollAction();
        action.setScroll(scrollId());

        // We'd like to get about 1 request a second
        task.rethrottle(1f);
        // Make the last scroll look nearly instant
        action.setLastBatchStartTime(System.nanoTime());
        // The last batch had 100 documents
        action.startNextScroll(100);

        // So the next request is going to have to wait an extra 100 seconds or so (base was 10, so 110ish)
        assertThat(client.lastScroll.get().request.scroll().keepAlive().seconds(), either(equalTo(110L)).or(equalTo(109L)));

        // Now we can simulate a response and check the delay that we used for the task
        InternalSearchHit hit = new InternalSearchHit(0, "id", new Text("type"), Collections.<String, SearchHitField>emptyMap());
        InternalSearchHits hits = new InternalSearchHits(new InternalSearchHit[] { hit }, 0, 0);
        InternalSearchResponse searchResponse = new InternalSearchResponse(hits, null, null, null, false, false);
        client.lastScroll.get().listener.onResponse(new SearchResponse(searchResponse, scrollId(), 5, 4, randomLong(), null));

        // The delay is still 100ish seconds because there hasn't been much time between when we requested the bulk and when we got it.
        assertThat(capturedDelay.get().seconds(), either(equalTo(100L)).or(equalTo(99L)));

        // Running the command ought to increment the delay counter on the task.
        capturedCommand.get().run();
        assertEquals(capturedDelay.get(), task.getStatus().getThrottled());
    }

    private long retryTestCase(boolean failWithRejection) throws Exception {
        int totalFailures = randomIntBetween(1, mainRequest.getMaxRetries());
        int size = randomIntBetween(1, 100);
        final int retryAttempts = totalFailures - (failWithRejection ? 1 : 0);

        client.bulksToReject = client.bulksAttempts.get() + totalFailures;
        /*
         * When we get a successful bulk response we usually start the next scroll request but lets just intercept that so we don't have to
         * deal with it. We just wait for it to happen.
         */
        final CountDownLatch successLatch = new CountDownLatch(1);
        DummyAbstractAsyncBulkByScrollAction action = new DummyAbstractAsyncBulkByScrollAction() {
            @Override
            BackoffPolicy backoffPolicy() {
                // Force a backoff time of 0 to prevent sleeping
                return constantBackoff(timeValueMillis(0), retryAttempts);
            }

            @Override
            void startNextScroll(int lastBatchSize) {
                successLatch.countDown();
            }
        };
        BulkRequest request = new BulkRequest(mainRequest);
        for (int i = 0; i < size + 1; i++) {
            request.add(new IndexRequest("index", "type", "id" + i));
        }
        action.sendBulkRequest(request);
        if (failWithRejection) {
            BulkIndexByScrollResponse response = listener.get();
            assertThat(response.getIndexingFailures(), hasSize(1));
            assertEquals(response.getIndexingFailures().get(0).getStatus(), RestStatus.TOO_MANY_REQUESTS);
            assertThat(response.getSearchFailures(), emptyCollectionOf(ShardSearchFailure.class));
            assertNull(response.getReasonCancelled());
        } else {
            successLatch.await(10, TimeUnit.SECONDS);
        }
        return retryAttempts;
    }

    /**
     * The default retry time matches what we say it is in the javadoc for the request.
     */
    public void testDefaultRetryTimes() {
        Iterator<TimeValue> policy = new DummyAbstractAsyncBulkByScrollAction().backoffPolicy().iterator();
        long millis = 0;
        while (policy.hasNext()) {
            millis += policy.next().millis();
        }
        /*
         * This is the total number of milliseconds that a reindex made with the default settings will backoff before attempting one final
         * time. If that request is rejected then the whole process fails with a rejected exception.
         */
        int defaultBackoffBeforeFailing = 59460;
        assertEquals(defaultBackoffBeforeFailing, millis);
    }

    public void testRefreshIsFalseByDefault() throws Exception {
        refreshTestCase(null, true, false);
    }

    public void testRefreshFalseDoesntExecuteRefresh() throws Exception {
        refreshTestCase(false, true, false);
    }

    public void testRefreshTrueExecutesRefresh() throws Exception {
        refreshTestCase(true, true, true);
    }

    public void testRefreshTrueSkipsRefreshIfNoDestinationIndexes() throws Exception {
        refreshTestCase(true, false, false);
    }

    private void refreshTestCase(Boolean refresh, boolean addDestinationIndexes, boolean shouldRefresh) {
        if (refresh != null) {
            mainRequest.setRefresh(refresh);
        }
        DummyAbstractAsyncBulkByScrollAction action = new DummyAbstractAsyncBulkByScrollAction();
        if (addDestinationIndexes) {
            action.addDestinationIndices(singleton("foo"));
        }
        action.startNormalTermination(Collections.<Failure>emptyList(), Collections.<ShardSearchFailure>emptyList(), false);
        if (shouldRefresh) {
            assertArrayEquals(new String[] {"foo"}, client.lastRefreshRequest.get().indices());
        } else {
            assertNull("No refresh was attempted", client.lastRefreshRequest.get());
        }
    }

    public void testCancelBeforeInitialSearch() throws Exception {
        cancelTaskCase(new Consumer<DummyAbstractAsyncBulkByScrollAction>() {
            @Override
            public void accept(DummyAbstractAsyncBulkByScrollAction action) {
                action.start();
            }
        });
    }

    public void testCancelBeforeScrollResponse() throws Exception {
        // We bail so early we don't need to pass in a half way valid response.
        cancelTaskCase(new Consumer<DummyAbstractAsyncBulkByScrollAction>() {
            @Override
            public void accept(DummyAbstractAsyncBulkByScrollAction action) {
                action.onScrollResponse(timeValueSeconds(0), null);
            }
        });
    }

    public void testCancelBeforeSendBulkRequest() throws Exception {
        // We bail so early we don't need to pass in a half way valid request.
        cancelTaskCase(new Consumer<DummyAbstractAsyncBulkByScrollAction>() {
            @Override
            public void accept(DummyAbstractAsyncBulkByScrollAction action) {
                action.sendBulkRequest(null);
            }
        });
    }

    public void testCancelBeforeOnBulkResponse() throws Exception {
        // We bail so early we don't need to pass in a half way valid response.
        cancelTaskCase(new Consumer<DummyAbstractAsyncBulkByScrollAction>() {
            @Override
            public void accept(DummyAbstractAsyncBulkByScrollAction action) {
                action.onBulkResponse(null);
            }
        });
    }

    public void testCancelBeforeStartNextScroll() throws Exception {
        cancelTaskCase(new Consumer<DummyAbstractAsyncBulkByScrollAction>() {
            @Override
            public void accept(DummyAbstractAsyncBulkByScrollAction action) {
                action.startNextScroll(0);
            }
        });
    }

    public void testCancelBeforeStartNormalTermination() throws Exception {
        // Refresh or not doesn't matter - we don't try to refresh.
        mainRequest.setRefresh(usually());
        cancelTaskCase(new Consumer<DummyAbstractAsyncBulkByScrollAction>() {
            @Override
            public void accept(DummyAbstractAsyncBulkByScrollAction action) {
                action.startNormalTermination(Collections.<Failure>emptyList(), Collections.<ShardSearchFailure>emptyList(), false);
            }
        });
        // This wouldn't return if we called refresh - the action would hang waiting for the refresh that we haven't mocked.
        assertNull("No refresh was attempted", client.lastRefreshRequest.get());
    }

    public void testRefuseToRunWithOldNodes() {
        smallestNonClientVersion = Version.V_2_2_0;
        try {
            new DummyAbstractAsyncBulkByScrollAction();
            fail("Expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertEquals("Refusing to execute [DummyRequest] because the entire cluster has not been upgraded to 2.3", e.getMessage());
        }
    }

    /**
     * Tests that we can cancel the request during its throttling delay. This can't use {@link #cancelTaskCase(Consumer)} because it needs
     * to send the request un-canceled and cancel it at a specific time.
     */
    public void testCancelWhileDelayedAfterScrollResponse() throws Exception {
        final String reason = randomSimpleString(random());

        /*
         * Replace the thread pool with one that will cancel the task as soon as anything is scheduled, which reindex tries to do when there
         * is a delay.
         */
        threadPool.shutdown();
        threadPool = new ThreadPool(getTestName()) {
            @Override
            public ScheduledFuture<?> schedule(TimeValue delay, String name, Runnable command) {
                /*
                 * This is called twice:
                 * 1. To schedule the throttling. When that happens we immediately cancel the task.
                 * 2. After the task is canceled.
                 * Both times we use delegate to the standard behavior so the task is scheduled as expected so it can be cancelled and all
                 * that good stuff.
                 */
                if (delay.nanos() > 0) {
                    generic().execute(new Runnable() {
                        @Override
                        public void run() {
                            taskManager.cancel(task, reason, new Consumer<Set<String>>() {
                                @Override
                                public void accept(Set<String> t) {
                                    // Intentionally a noop.
                                }
                            });
                        }
                    });
                }
                return super.schedule(delay, name, command);
            }
        };

        // Send the scroll response which will trigger the custom thread pool above, canceling the request before running the response
        DummyAbstractAsyncBulkByScrollAction action = new DummyAbstractAsyncBulkByScrollAction();
        boolean previousScrollSet = usually();
        if (previousScrollSet) {
            action.setScroll(scrollId());
        }
        long total = randomIntBetween(0, Integer.MAX_VALUE);
        InternalSearchHits hits = new InternalSearchHits(null, total, 0);
        InternalSearchResponse searchResponse = new InternalSearchResponse(hits, null, null, null, false, false);
        // Use a long delay here so the test will time out if the cancellation doesn't reschedule the throttled task
        action.onScrollResponse(timeValueMinutes(10), new SearchResponse(searchResponse, scrollId(), 5, 4, randomLong(), null));

        // Now that we've got our cancel we'll just verify that it all came through all right
        assertEquals(reason, listener.get(10, TimeUnit.SECONDS).getReasonCancelled());
        if (previousScrollSet) {
            // Canceled tasks always start to clear the scroll before they die.
            assertThat(client.scrollsCleared, contains(scrollId));
        }
    }

    private void cancelTaskCase(Consumer<DummyAbstractAsyncBulkByScrollAction> testMe) throws Exception {
        DummyAbstractAsyncBulkByScrollAction action = new DummyAbstractAsyncBulkByScrollAction();
        boolean previousScrollSet = usually();
        if (previousScrollSet) {
            action.setScroll(scrollId());
        }
        String reason = randomSimpleString(random());
        taskManager.cancel(task, reason, new Consumer<Set<String>>() {
            @Override
            public void accept(Set<String> t) {
                // Noop!
            }
        });
        testMe.accept(action);
        assertEquals(reason, listener.get().getReasonCancelled());
        if (previousScrollSet) {
            // Canceled tasks always start to clear the scroll before they die.
            assertThat(client.scrollsCleared, contains(scrollId));
        }
    }

    private class DummyAbstractAsyncBulkByScrollAction
            extends AbstractAsyncBulkByScrollAction<DummyAbstractBulkByScrollRequest, BulkIndexByScrollResponse> {
        public DummyAbstractAsyncBulkByScrollAction() {
            super(AsyncBulkByScrollActionTests.this.task, logger, client, threadPool, smallestNonClientVersion,
                    AsyncBulkByScrollActionTests.this.mainRequest, firstSearchRequest, listener);
        }

        @Override
        protected BulkRequest buildBulk(Iterable<SearchHit> docs) {
            return new BulkRequest();
        }

        @Override
        protected BulkIndexByScrollResponse buildResponse(TimeValue took, List<Failure> indexingFailures,
                List<ShardSearchFailure> searchFailures, boolean timedOut) {
            return new BulkIndexByScrollResponse(took, task.getStatus(), indexingFailures, searchFailures, timedOut);
        }
    }

    private static class DummyAbstractBulkByScrollRequest extends AbstractBulkByScrollRequest<DummyAbstractBulkByScrollRequest> {
        public DummyAbstractBulkByScrollRequest() {
            super(new SearchRequest());
        }

        @Override
        protected DummyAbstractBulkByScrollRequest self() {
            return this;
        }

        @Override
        public String toString() {
            return "DummyRequest";
        }
    }

    private class MyMockClient extends FilterClient {
        private final List<String> scrollsCleared = new ArrayList<>();
        private final AtomicInteger bulksAttempts = new AtomicInteger();
        private final AtomicReference<SearchRequest> lastSentSearchRequest = new AtomicReference<>();
        private final AtomicReference<RefreshRequest> lastRefreshRequest = new AtomicReference<>();
        private final AtomicReference<RequestAndListener<SearchScrollRequest, SearchResponse>> lastScroll = new AtomicReference<>();

        private int bulksToReject = 0;

        public MyMockClient(Client in) {
            super(in);
        }

        @Override
        @SuppressWarnings({ "unchecked", "rawtypes" })
        protected <
                    Request extends ActionRequest,
                    Response extends ActionResponse,
                    RequestBuilder extends ActionRequestBuilder<Request, Response, RequestBuilder>
                > void doExecute(Action<Request, Response, RequestBuilder> action, Request request, ActionListener<Response> listener) {
            assertEquals(request + "preserved context", mainRequest.getContext(), request.getContext());
            assertEquals(request + "preserved headers", mainRequest.getHeaders(), request.getHeaders());
            if (request instanceof SearchRequest) {
                lastSentSearchRequest.set((SearchRequest) request);
                return;
            }
            if (request instanceof RefreshRequest) {
                lastRefreshRequest.set((RefreshRequest) request);
                listener.onResponse(null);
                return;
            }
            if (request instanceof SearchScrollRequest) {
                lastScroll.set(new RequestAndListener<>((SearchScrollRequest) request, (ActionListener<SearchResponse>) listener));
                return;
            }
            if (request instanceof ClearScrollRequest) {
                ClearScrollRequest clearScroll = (ClearScrollRequest) request;
                scrollsCleared.addAll(clearScroll.getScrollIds());
                listener.onResponse((Response) new ClearScrollResponse(true, clearScroll.getScrollIds().size()));
                return;
            }
            if (request instanceof BulkRequest) {
                BulkRequest bulk = (BulkRequest) request;
                int toReject;
                if (bulksAttempts.incrementAndGet() > bulksToReject) {
                    toReject = -1;
                } else {
                    toReject = randomIntBetween(0, bulk.requests().size() - 1);
                }
                BulkItemResponse[] responses = new BulkItemResponse[bulk.requests().size()];
                for (int i = 0; i < bulk.requests().size(); i++) {
                    ActionRequest<?> item = bulk.requests().get(i);
                    String opType;
                    ActionWriteResponse response;
                    String type;
                    String id;
                    ShardId shardId = new ShardId(new Index(((ReplicationRequest<?>) item).index()), 0);
                    if (item instanceof IndexRequest) {
                        IndexRequest index = (IndexRequest) item;
                        opType = index.opType().lowercase();
                        type = index.type();
                        id = index.id();
                        response = new IndexResponse(shardId.getIndex(), type, id, randomIntBetween(0, Integer.MAX_VALUE), true);
                    } else if (item instanceof UpdateRequest) {
                        UpdateRequest update = (UpdateRequest) item;
                        opType = "update";
                        type = update.type();
                        id = update.id();
                        response = new UpdateResponse(shardId.getIndex(), type, id, randomIntBetween(0, Integer.MAX_VALUE), true);
                    } else if (item instanceof DeleteRequest) {
                        DeleteRequest delete = (DeleteRequest) item;
                        opType = "delete";
                        type = delete.type();
                        id = delete.id();
                        response = new DeleteResponse(shardId.getIndex(), type, id, randomIntBetween(0, Integer.MAX_VALUE), true);
                    } else {
                        throw new RuntimeException("Unknown request:  " + item);
                    }
                    if (i == toReject) {
                        responses[i] = new BulkItemResponse(i, opType,
                                new Failure(shardId.getIndex(), type, id, new EsRejectedExecutionException()));
                    } else {
                        responses[i] = new BulkItemResponse(i, opType, response);
                    }
                }
                listener.onResponse((Response) new BulkResponse(responses, 1));
                return;
            }
            super.doExecute(action, request, listener);
        }
    }

    private static class RequestAndListener<Request extends ActionRequest<Request>, Response> {
        private final Request request;
        private final ActionListener<Response> listener;

        public RequestAndListener(Request request, ActionListener<Response> listener) {
            this.request = request;
            this.listener = listener;
        }
    }
}
