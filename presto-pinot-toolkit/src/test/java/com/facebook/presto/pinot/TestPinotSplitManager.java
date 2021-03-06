/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.pinot;

import com.facebook.presto.pinot.query.PinotQueryGenerator;
import com.facebook.presto.spi.ConnectorSession;
import com.facebook.presto.spi.ConnectorSplitSource;
import com.facebook.presto.spi.plan.PlanNode;
import com.facebook.presto.sql.analyzer.FeaturesConfig;
import com.facebook.presto.sql.planner.iterative.rule.test.PlanBuilder;
import com.facebook.presto.testing.TestingConnectorSession;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;

import static com.facebook.airlift.concurrent.MoreFutures.getFutureValue;
import static com.facebook.presto.common.type.TimeZoneKey.UTC_KEY;
import static com.facebook.presto.pinot.PinotSplit.SplitType.BROKER;
import static com.facebook.presto.pinot.PinotSplit.SplitType.SEGMENT;
import static com.facebook.presto.spi.connector.NotPartitionedPartitionHandle.NOT_PARTITIONED;
import static java.util.Locale.ENGLISH;
import static java.util.stream.Collectors.toList;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class TestPinotSplitManager
        extends TestPinotQueryBase
{
    // Test table and related info
    private final PinotConfig pinotConfig = new PinotConfig();
    private final PinotConnection pinotConnection = new PinotConnection(new MockPinotClusterInfoFetcher(pinotConfig), pinotConfig, Executors.newSingleThreadExecutor());
    private final PinotSplitManager pinotSplitManager = new PinotSplitManager(pinotConnectorId, pinotConnection);

    @Test
    public void testRealtimeSegmentSplitsOneSegmentPerServer()
    {
        testSegmentSplitsHelperNoFilter(realtimeOnlyTable, 1, 4, false);  // 2 servers with 2 segments each
    }

    private void testSegmentSplitsHelperNoFilter(PinotTableHandle table, int segmentsPerSplit, int expectedNumSplits, boolean expectFilter)
    {
        PinotConfig pinotConfig = new PinotConfig().setForbidBrokerQueries(false);
        SessionHolder sessionHolder = new SessionHolder(pinotConfig);
        PlanBuilder planBuilder = createPlanBuilder(sessionHolder);
        PlanNode plan = tableScan(planBuilder, table, regionId, city, fare, secondsSinceEpoch);
        PinotQueryGenerator.PinotQueryGeneratorResult pinotQueryGeneratorResult = new PinotQueryGenerator(pinotConfig, typeManager, functionMetadataManager, standardFunctionResolution).generate(plan, sessionHolder.getConnectorSession()).get();
        List<PinotColumnHandle> expectedHandles = ImmutableList.copyOf(pinotQueryGeneratorResult.getContext().getAssignments().values());
        PinotQueryGenerator.GeneratedPinotQuery generatedPql = pinotQueryGeneratorResult.getGeneratedPinotQuery();
        PinotTableHandle pinotTableHandle = new PinotTableHandle(table.getConnectorId(), table.getSchemaName(), table.getTableName(), Optional.of(false), Optional.of(expectedHandles), Optional.of(generatedPql));
        List<PinotSplit> splits = getSplitsHelper(pinotTableHandle, segmentsPerSplit, false);
        assertSplits(splits, expectedNumSplits, SEGMENT);
        splits.forEach(s -> assertSegmentSplitWellFormed(s, expectFilter));
    }

    private void testSegmentSplitsHelperWithFilter(PinotTableHandle table, int segmentsPerSplit, int expectedNumSplits)
    {
        PinotConfig pinotConfig = new PinotConfig().setForbidBrokerQueries(false);
        SessionHolder sessionHolder = new SessionHolder(pinotConfig);
        PlanBuilder planBuilder = createPlanBuilder(sessionHolder);
        PlanNode plan = filter(planBuilder, tableScan(planBuilder, table, regionId, city, fare, secondsSinceEpoch), getRowExpression("city = 'Boston'", sessionHolder));
        PinotQueryGenerator.PinotQueryGeneratorResult pinotQueryGeneratorResult = new PinotQueryGenerator(pinotConfig, typeManager, functionMetadataManager, standardFunctionResolution).generate(plan, sessionHolder.getConnectorSession()).get();
        List<PinotColumnHandle> expectedHandles = ImmutableList.copyOf(pinotQueryGeneratorResult.getContext().getAssignments().values());
        PinotQueryGenerator.GeneratedPinotQuery generatedPql = pinotQueryGeneratorResult.getGeneratedPinotQuery();
        PinotTableHandle pinotTableHandle = new PinotTableHandle(table.getConnectorId(), table.getSchemaName(), table.getTableName(), Optional.of(false), Optional.of(expectedHandles), Optional.of(generatedPql));
        List<PinotSplit> splits = getSplitsHelper(pinotTableHandle, segmentsPerSplit, false);
        assertSplits(splits, expectedNumSplits, SEGMENT);
        splits.forEach(s -> assertSegmentSplitWellFormed(s, true));
    }

    @Test
    public void testSplitsBroker()
    {
        PinotQueryGenerator.GeneratedPinotQuery generatedPql = new PinotQueryGenerator.GeneratedPinotQuery(realtimeOnlyTable.getTableName(), String.format("SELECT %s, COUNT(1) FROM %s GROUP BY %s TOP %d", city.getColumnName(), realtimeOnlyTable.getTableName(), city.getColumnName(), pinotConfig.getTopNLarge()), PinotQueryGenerator.PinotQueryFormat.PQL, ImmutableList.of(0, 1), 1, false, true);
        PinotTableHandle pinotTableHandle = new PinotTableHandle(realtimeOnlyTable.getConnectorId(), realtimeOnlyTable.getSchemaName(), realtimeOnlyTable.getTableName(), Optional.of(true), Optional.of(ImmutableList.of(city, derived("count"))), Optional.of(generatedPql));
        List<PinotSplit> splits = getSplitsHelper(pinotTableHandle, 1, false);
        assertSplits(splits, 1, BROKER);
    }

    @Test(expectedExceptions = PinotSplitManager.QueryNotAdequatelyPushedDownException.class)
    public void testBrokerNonShortQuery()
    {
        PinotQueryGenerator.GeneratedPinotQuery generatedPql = new PinotQueryGenerator.GeneratedPinotQuery(realtimeOnlyTable.getTableName(), String.format("SELECT %s FROM %s", city.getColumnName(), realtimeOnlyTable.getTableName()), PinotQueryGenerator.PinotQueryFormat.PQL, ImmutableList.of(0), 0, false, false);
        PinotTableHandle pinotTableHandle = new PinotTableHandle(realtimeOnlyTable.getConnectorId(), realtimeOnlyTable.getSchemaName(), realtimeOnlyTable.getTableName(), Optional.of(false), Optional.of(ImmutableList.of(city)), Optional.of(generatedPql));
        List<PinotSplit> splits = getSplitsHelper(pinotTableHandle, 1, true);
        assertSplits(splits, 1, BROKER);
    }

    @Test
    public void testRealtimeSegmentSplitsManySegmentPerServer()
    {
        testSegmentSplitsHelperNoFilter(realtimeOnlyTable, Integer.MAX_VALUE, 2, false);
    }

    @Test
    public void testHybridSegmentSplitsOneSegmentPerServer()
    {
        testSegmentSplitsHelperNoFilter(hybridTable, 1, 8, true);
        testSegmentSplitsHelperWithFilter(hybridTable, 1, 8);
    }

    private void assertSplits(List<PinotSplit> splits, int numSplitsExpected, PinotSplit.SplitType splitType)
    {
        assertEquals(splits.size(), numSplitsExpected);
        splits.forEach(s -> assertEquals(s.getSplitType(), splitType));
    }

    private void assertSegmentSplitWellFormed(PinotSplit split, boolean expectFilter)
    {
        assertEquals(split.getSplitType(), SEGMENT);
        assertTrue(split.getSegmentPql().isPresent());
        assertTrue(split.getSegmentHost().isPresent());
        assertFalse(split.getSegments().isEmpty());
        String pql = split.getSegmentPql().get();
        assertFalse(pql.contains("__")); // templates should be fully resolved
        List<String> splitOnWhere = Splitter.on(" WHERE ").splitToList(pql);
        // There should be exactly one WHERE clause and it should partition the pql into two
        assertEquals(splitOnWhere.size(), expectFilter ? 2 : 1, "Expected to find only one WHERE clause in " + pql);
    }

    public static ConnectorSession createSessionWithNumSplits(int numSegmentsPerSplit, boolean forbidSegmentQueries, PinotConfig pinotConfig)
    {
        return new TestingConnectorSession(
                "user",
                Optional.of("test"),
                Optional.empty(),
                UTC_KEY,
                ENGLISH,
                System.currentTimeMillis(),
                new PinotSessionProperties(pinotConfig).getSessionProperties(),
                ImmutableMap.of(
                        PinotSessionProperties.NUM_SEGMENTS_PER_SPLIT,
                        numSegmentsPerSplit,
                        PinotSessionProperties.FORBID_SEGMENT_QUERIES,
                        forbidSegmentQueries),
                new FeaturesConfig().isLegacyTimestamp(),
                Optional.empty());
    }

    private List<PinotSplit> getSplitsHelper(PinotTableHandle pinotTable, int numSegmentsPerSplit, boolean forbidSegmentQueries)
    {
        PinotTableLayoutHandle pinotTableLayout = new PinotTableLayoutHandle(pinotTable);
        ConnectorSession session = createSessionWithNumSplits(numSegmentsPerSplit, forbidSegmentQueries, pinotConfig);
        ConnectorSplitSource splitSource = pinotSplitManager.getSplits(null, session, pinotTableLayout, null);
        List<PinotSplit> splits = new ArrayList<>();
        while (!splitSource.isFinished()) {
            splits.addAll(getFutureValue(splitSource.getNextBatch(NOT_PARTITIONED, 1000)).getSplits().stream().map(s -> (PinotSplit) s).collect(toList()));
        }

        return splits;
    }
}
