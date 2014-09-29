/*
 * Druid - a distributed column store.
 * Copyright (C) 2012, 2013  Metamarkets Group Inc.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package io.druid.segment.incremental;

import com.google.common.base.Function;
import com.google.common.base.Throwables;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import com.metamx.common.IAE;
import com.metamx.common.ISE;
import io.druid.collections.ResourceHolder;
import io.druid.collections.StupidPool;
import io.druid.data.input.InputRow;
import io.druid.data.input.MapBasedRow;
import io.druid.data.input.Row;
import io.druid.data.input.impl.SpatialDimensionSchema;
import io.druid.granularity.QueryGranularity;
import io.druid.query.aggregation.AggregatorFactory;
import io.druid.query.aggregation.BufferAggregator;
import io.druid.query.aggregation.PostAggregator;
import io.druid.segment.ColumnSelectorFactory;
import io.druid.segment.DimensionSelector;
import io.druid.segment.FloatColumnSelector;
import io.druid.segment.ObjectColumnSelector;
import io.druid.segment.TimestampColumnSelector;
import io.druid.segment.column.ColumnCapabilities;
import io.druid.segment.column.ColumnCapabilitiesImpl;
import io.druid.segment.column.ValueType;
import io.druid.segment.data.IndexedInts;
import io.druid.segment.serde.ComplexMetricExtractor;
import io.druid.segment.serde.ComplexMetricSerde;
import io.druid.segment.serde.ComplexMetrics;
import org.joda.time.DateTime;
import org.joda.time.Interval;
import org.mapdb.BTreeKeySerializer;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.Serializer;

import javax.annotation.Nullable;
import java.io.Closeable;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.Serializable;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 */
public class IncrementalIndex implements Iterable<Row>, Closeable
{
  private final long minTimestamp;
  private final QueryGranularity gran;
  private final List<Function<InputRow, InputRow>> rowTransformers;
  private final AggregatorFactory[] metrics;
  private final Map<String, Integer> metricIndexes;
  private final Map<String, String> metricTypes;
  private final ImmutableList<String> metricNames;
  private final BufferAggregator[] aggs;
  private final int[] aggPositionOffsets;
  private final int totalAggSize;
  private final LinkedHashMap<String, Integer> dimensionOrder;
  private final CopyOnWriteArrayList<String> dimensions;
  private final DimensionHolder dimValues;
  private final Map<String, ColumnCapabilitiesImpl> columnCapabilities;
  private final ConcurrentNavigableMap<TimeAndDims, Integer> facts;
  private final ResourceHolder<ByteBuffer> bufferHolder;
  private final DB db;
  private final DB factsDb;
  private final boolean useOffheap;
  private volatile AtomicInteger numEntries = new AtomicInteger();
  // This is modified on add() in a critical section.
  private ThreadLocal<InputRow> in = new ThreadLocal<>();

  /**
   * Setting deserializeComplexMetrics to false is necessary for intermediate aggregation such as groupBy that
   * should not deserialize input columns using ComplexMetricSerde for aggregators that return complex metrics.
   *
   * @param incrementalIndexSchema
   * @param bufferPool
   * @param deserializeComplexMetrics flag whether or not to call ComplexMetricExtractor.extractValue() on the input
   *                                  value for aggregators that return metrics other than float.
   */
  public IncrementalIndex(
      IncrementalIndexSchema incrementalIndexSchema,
      StupidPool<ByteBuffer> bufferPool,
      final boolean deserializeComplexMetrics,
      final boolean useOffheap
  )
  {
    this.minTimestamp = incrementalIndexSchema.getMinTimestamp();
    this.gran = incrementalIndexSchema.getGran();
    this.metrics = incrementalIndexSchema.getMetrics();
    this.rowTransformers = Lists.newCopyOnWriteArrayList();

    final ImmutableList.Builder<String> metricNamesBuilder = ImmutableList.builder();
    final ImmutableMap.Builder<String, Integer> metricIndexesBuilder = ImmutableMap.builder();
    final ImmutableMap.Builder<String, String> metricTypesBuilder = ImmutableMap.builder();
    this.aggs = new BufferAggregator[metrics.length];
    this.aggPositionOffsets = new int[metrics.length];
    int currAggSize = 0;
    for (int i = 0; i < metrics.length; i++) {
      final AggregatorFactory agg = metrics[i];
      aggs[i] = agg.factorizeBuffered(
          new ColumnSelectorFactory()
          {
            @Override
            public TimestampColumnSelector makeTimestampColumnSelector()
            {
              return new TimestampColumnSelector()
              {
                @Override
                public long getTimestamp()
                {
                  return in.get().getTimestampFromEpoch();
                }
              };
            }

            @Override
            public FloatColumnSelector makeFloatColumnSelector(String columnName)
            {
              final String metricName = columnName.toLowerCase();
              return new FloatColumnSelector()
              {
                @Override
                public float get()
                {
                  return in.get().getFloatMetric(metricName);
                }
              };
            }

            @Override
            public ObjectColumnSelector makeObjectColumnSelector(String column)
            {
              final String typeName = agg.getTypeName();
              final String columnName = column.toLowerCase();

              final ObjectColumnSelector<Object> rawColumnSelector = new ObjectColumnSelector<Object>()
              {
                @Override
                public Class classOfObject()
                {
                  return Object.class;
                }

                @Override
                public Object get()
                {
                  return in.get().getRaw(columnName);
                }
              };

              if (!deserializeComplexMetrics) {
                return rawColumnSelector;
              } else {
                if (typeName.equals("float")) {
                  return rawColumnSelector;
                }

                final ComplexMetricSerde serde = ComplexMetrics.getSerdeForType(typeName);
                if (serde == null) {
                  throw new ISE("Don't know how to handle type[%s]", typeName);
                }

                final ComplexMetricExtractor extractor = serde.getExtractor();
                return new ObjectColumnSelector()
                {
                  @Override
                  public Class classOfObject()
                  {
                    return extractor.extractedClass();
                  }

                  @Override
                  public Object get()
                  {
                    return extractor.extractValue(in.get(), columnName);
                  }
                };
              }
            }

            @Override
            public DimensionSelector makeDimensionSelector(final String dimension)
            {
              final String dimensionName = dimension.toLowerCase();
              return new DimensionSelector()
              {
                @Override
                public IndexedInts getRow()
                {
                  final List<String> dimensionValues = in.get().getDimension(dimensionName);
                  final ArrayList<Integer> vals = Lists.newArrayList();
                  if (dimensionValues != null) {
                    for (int i = 0; i < dimensionValues.size(); ++i) {
                      vals.add(i);
                    }
                  }

                  return new IndexedInts()
                  {
                    @Override
                    public int size()
                    {
                      return vals.size();
                    }

                    @Override
                    public int get(int index)
                    {
                      return vals.get(index);
                    }

                    @Override
                    public Iterator<Integer> iterator()
                    {
                      return vals.iterator();
                    }
                  };
                }

                @Override
                public int getValueCardinality()
                {
                  throw new UnsupportedOperationException("value cardinality is unknown in incremental index");
                }

                @Override
                public String lookupName(int id)
                {
                  return in.get().getDimension(dimensionName).get(id);
                }

                @Override
                public int lookupId(String name)
                {
                  return in.get().getDimension(dimensionName).indexOf(name);
                }
              };
            }
          }
      );
      aggPositionOffsets[i] = currAggSize;
      currAggSize += agg.getMaxIntermediateSize();
      final String metricName = metrics[i].getName().toLowerCase();
      metricNamesBuilder.add(metricName);
      metricIndexesBuilder.put(metricName, i);
      metricTypesBuilder.put(metricName, metrics[i].getTypeName());
    }
    metricNames = metricNamesBuilder.build();
    metricIndexes = metricIndexesBuilder.build();
    metricTypes = metricTypesBuilder.build();

    this.totalAggSize = currAggSize;

    this.dimensionOrder = Maps.newLinkedHashMap();
    this.dimensions = new CopyOnWriteArrayList<>();
    // This should really be more generic
    List<SpatialDimensionSchema> spatialDimensions = incrementalIndexSchema.getDimensionsSpec().getSpatialDimensions();
    if (!spatialDimensions.isEmpty()) {
      this.rowTransformers.add(new SpatialDimensionRowTransformer(spatialDimensions));
    }

    this.columnCapabilities = Maps.newHashMap();
    for (Map.Entry<String, String> entry : metricTypes.entrySet()) {
      ValueType type;
      if (entry.getValue().equalsIgnoreCase("float")) {
        type = ValueType.FLOAT;
      } else {
        type = ValueType.COMPLEX;
      }
      ColumnCapabilitiesImpl capabilities = new ColumnCapabilitiesImpl();
      capabilities.setType(type);
      columnCapabilities.put(entry.getKey(), capabilities);
    }
    for (String dimension : dimensions) {
      ColumnCapabilitiesImpl capabilities = new ColumnCapabilitiesImpl();
      capabilities.setType(ValueType.STRING);
      columnCapabilities.put(dimension, capabilities);
    }
    for (SpatialDimensionSchema spatialDimension : spatialDimensions) {
      ColumnCapabilitiesImpl capabilities = new ColumnCapabilitiesImpl();
      capabilities.setType(ValueType.STRING);
      capabilities.setHasSpatialIndexes(true);
      columnCapabilities.put(spatialDimension.getDimName(), capabilities);
    }
    this.bufferHolder = bufferPool.take();
    this.dimValues = new DimensionHolder();
    this.useOffheap = true;
    if (this.useOffheap) {
      final DBMaker dbMaker = DBMaker.newMemoryDirectDB().transactionDisable().asyncWriteEnable().cacheSoftRefEnable();
      db = dbMaker.make();
      factsDb = dbMaker.make();
      final TimeAndDimsSerializer timeAndDimsSerializer = new TimeAndDimsSerializer(this);
      this.facts = factsDb.createTreeMap("__facts" + UUID.randomUUID())
                     .keySerializer(timeAndDimsSerializer)
                     .comparator(timeAndDimsSerializer.getComparator())
                     .valueSerializer(Serializer.INTEGER)
                     .make();
    } else {
      db = null;
      factsDb = null;
      this.facts = new ConcurrentSkipListMap<>();
    }
  }

  public IncrementalIndex(
      long minTimestamp,
      QueryGranularity gran,
      final AggregatorFactory[] metrics,
      StupidPool<ByteBuffer> bufferPool
  )
  {
    this(
        new IncrementalIndexSchema.Builder().withMinTimestamp(minTimestamp)
                                            .withQueryGranularity(gran)
                                            .withMetrics(metrics)
                                            .build(),
        bufferPool,
        true,
        false
    );
  }

  public IncrementalIndex(
      IncrementalIndexSchema incrementalIndexSchema,
      StupidPool<ByteBuffer> bufferPool,
      boolean useOffheap
  )
  {
    this(incrementalIndexSchema, bufferPool, true, useOffheap);
  }

  public IncrementalIndex(
      long minTimestamp,
      QueryGranularity gran,
      final AggregatorFactory[] metrics,
      StupidPool<ByteBuffer> bufferPool,
      boolean deserializeComplexMetrics,
      boolean useOffheap
  )
  {
    this(
        new IncrementalIndexSchema.Builder().withMinTimestamp(minTimestamp)
                                            .withQueryGranularity(gran)
                                            .withMetrics(metrics)
                                            .build(),
        bufferPool,
        deserializeComplexMetrics,
        useOffheap
    );
  }

  public InputRow formatRow(InputRow row)
  {
    for (Function<InputRow, InputRow> rowTransformer : rowTransformers) {
      row = rowTransformer.apply(row);
    }

    if (row == null) {
      throw new IAE("Row is null? How can this be?!");
    }
    return row;
  }

  /**
   * Adds a new row.  The row might correspond with another row that already exists, in which case this will
   * update that row instead of inserting a new one.
   * <p/>
   * <p/>
   * Calls to add() are thread safe.
   * <p/>
   *
   * @param row the row of data to add
   *
   * @return the number of rows in the data set after adding the InputRow
   */
  public int add(InputRow row)
  {
    row = formatRow(row);
    if (row.getTimestampFromEpoch() < minTimestamp) {
      throw new IAE("Cannot add row[%s] because it is below the minTimestamp[%s]", row, new DateTime(minTimestamp));
    }

    final List<String> rowDimensions = row.getDimensions();

    String[][] dims;
    List<String[]> overflow = null;
    synchronized (dimensionOrder) {
      dims = new String[dimensionOrder.size()][];
      for (String dimension : rowDimensions) {
        dimension = dimension.toLowerCase();
        List<String> dimensionValues = row.getDimension(dimension);

        // Set column capabilities as data is coming in
        ColumnCapabilitiesImpl capabilities = columnCapabilities.get(dimension);
        if (capabilities == null) {
          capabilities = new ColumnCapabilitiesImpl();
          capabilities.setType(ValueType.STRING);
          columnCapabilities.put(dimension, capabilities);
        }
        if (dimensionValues.size() > 1) {
          capabilities.setHasMultipleValues(true);
        }

        Integer index = dimensionOrder.get(dimension);
        if (index == null) {
          dimensionOrder.put(dimension, dimensionOrder.size());
          dimensions.add(dimension);

          if (overflow == null) {
            overflow = Lists.newArrayList();
          }
          overflow.add(getDimVals(dimValues.add(dimension), dimensionValues));
        } else {
          dims[index] = getDimVals(dimValues.get(dimension), dimensionValues);
        }
      }
    }


    if (overflow != null) {
      // Merge overflow and non-overflow
      String[][] newDims = new String[dims.length + overflow.size()][];
      System.arraycopy(dims, 0, newDims, 0, dims.length);
      for (int i = 0; i < overflow.size(); ++i) {
        newDims[dims.length + i] = overflow.get(i);
      }
      dims = newDims;
    }

    final TimeAndDims key = new TimeAndDims(Math.max(gran.truncate(row.getTimestampFromEpoch()), minTimestamp), dims);
    Integer rowOffset;
    synchronized (this) {
      rowOffset = totalAggSize * numEntries.get();
      final Integer prev = facts.putIfAbsent(key, rowOffset);
      if (prev != null) {
        rowOffset = prev;
      } else {
        if (rowOffset + totalAggSize > bufferHolder.get().limit()) {
          facts.remove(key);
          throw new ISE("Buffer full, cannot add more rows! Current rowSize[%,d].", numEntries.get());
        }
        numEntries.incrementAndGet();
        for (int i = 0; i < aggs.length; i++) {
          aggs[i].init(bufferHolder.get(), getMetricPosition(rowOffset, i));
        }
      }
    }
    in.set(row);
    for (int i = 0; i < aggs.length; i++) {
      synchronized (aggs[i]) {
        aggs[i].aggregate(bufferHolder.get(), getMetricPosition(rowOffset, i));
      }
    }
    in.set(null);
    return numEntries.get();
  }

  public boolean isEmpty()
  {
    return numEntries.get() == 0;
  }

  public int size()
  {
    return numEntries.get();
  }

  public long getMinTimeMillis()
  {
    return facts.firstKey().getTimestamp();
  }

  public long getMaxTimeMillis()
  {
    return facts.lastKey().getTimestamp();
  }

  private String[] getDimVals(final DimDim dimLookup, final List<String> dimValues)
  {
    final String[] retVal = new String[dimValues.size()];

    int count = 0;
    for (String dimValue : dimValues) {
      String canonicalDimValue = dimLookup.get(dimValue);
      if (!dimLookup.contains(canonicalDimValue)) {
        dimLookup.add(dimValue);
      }
      retVal[count] = canonicalDimValue;
      count++;
    }
    Arrays.sort(retVal);

    return retVal;
  }

  public AggregatorFactory[] getMetricAggs()
  {
    return metrics;
  }

  public List<String> getDimensions()
  {
    return dimensions;
  }

  public String getMetricType(String metric)
  {
    return metricTypes.get(metric);
  }

  public long getMinTimestamp()
  {
    return minTimestamp;
  }

  public QueryGranularity getGranularity()
  {
    return gran;
  }

  public Interval getInterval()
  {
    return new Interval(minTimestamp, isEmpty() ? minTimestamp : gran.next(getMaxTimeMillis()));
  }

  public DateTime getMinTime()
  {
    return isEmpty() ? null : new DateTime(getMinTimeMillis());
  }

  public DateTime getMaxTime()
  {
    return isEmpty() ? null : new DateTime(getMaxTimeMillis());
  }

  DimDim getDimension(String dimension)
  {
    return isEmpty() ? null : dimValues.get(dimension);
  }

  Integer getDimensionIndex(String dimension)
  {
    return dimensionOrder.get(dimension);
  }

  List<String> getMetricNames()
  {
    return metricNames;
  }

  Integer getMetricIndex(String metricName)
  {
    return metricIndexes.get(metricName);
  }

  int getMetricPosition(int rowOffset, int metricIndex)
  {
    return rowOffset + aggPositionOffsets[metricIndex];
  }

  ByteBuffer getMetricBuffer()
  {
    return bufferHolder.get();
  }

  BufferAggregator getAggregator(int metricIndex)
  {
    return aggs[metricIndex];
  }

  ColumnCapabilities getCapabilities(String column)
  {
    return columnCapabilities.get(column);
  }

  ConcurrentNavigableMap<TimeAndDims, Integer> getFacts()
  {
    return facts;
  }

  ConcurrentNavigableMap<TimeAndDims, Integer> getSubMap(TimeAndDims start, TimeAndDims end)
  {
    return facts.subMap(start, end);
  }

  @Override
  public Iterator<Row> iterator()
  {
    return iterableWithPostAggregations(null).iterator();
  }

  public Iterable<Row> iterableWithPostAggregations(final List<PostAggregator> postAggs)
  {
    return new Iterable<Row>()
    {
      @Override
      public Iterator<Row> iterator()
      {
        return Iterators.transform(
            facts.entrySet().iterator(),
            new Function<Map.Entry<TimeAndDims, Integer>, Row>()
            {
              @Override
              public Row apply(final Map.Entry<TimeAndDims, Integer> input)
              {
                final TimeAndDims timeAndDims = input.getKey();
                final int rowOffset = input.getValue();

                String[][] theDims = timeAndDims.getDims();

                Map<String, Object> theVals = Maps.newLinkedHashMap();
                for (int i = 0; i < theDims.length; ++i) {
                  String[] dim = theDims[i];
                  if (dim != null && dim.length != 0) {
                    theVals.put(dimensions.get(i), dim.length == 1 ? dim[0] : Arrays.asList(dim));
                  }
                }

                for (int i = 0; i < aggs.length; ++i) {
                  theVals.put(metrics[i].getName(), aggs[i].get(bufferHolder.get(), getMetricPosition(rowOffset, i)));
                }

                if (postAggs != null) {
                  for (PostAggregator postAgg : postAggs) {
                    theVals.put(postAgg.getName(), postAgg.compute(theVals));
                  }
                }

                return new MapBasedRow(timeAndDims.getTimestamp(), theVals);
              }
            }
        );
      }
    };
  }

  @Override
  public void close()
  {
    try {
      bufferHolder.close();
      if (db != null) {
        factsDb.close();
        db.close();
      }
    }
    catch (IOException e) {
      throw Throwables.propagate(e);
    }
  }

  class DimensionHolder
  {
    private final Map<String, DimDim> dimensions;

    DimensionHolder()
    {
      dimensions = Maps.newConcurrentMap();
    }

    void reset()
    {
      dimensions.clear();
    }

    DimDim add(String dimension)
    {
      DimDim holder = dimensions.get(dimension);
      if (holder == null) {
        holder = new DimDim(dimension);
        dimensions.put(dimension, holder);
      } else {
        throw new ISE("dimension[%s] already existed even though add() was called!?", dimension);
      }
      return holder;
    }

    DimDim get(String dimension)
    {
      return dimensions.get(dimension);
    }
  }

  static class TimeAndDims implements Comparable<TimeAndDims>
  {
    private final long timestamp;
    private final String[][] dims;

    TimeAndDims(
        long timestamp,
        String[][] dims
    )
    {
      this.timestamp = timestamp;
      this.dims = dims;
    }

    long getTimestamp()
    {
      return timestamp;
    }

    String[][] getDims()
    {
      return dims;
    }

    @Override
    public int compareTo(TimeAndDims rhs)
    {
      int retVal = Longs.compare(timestamp, rhs.timestamp);

      if (retVal == 0) {
        retVal = Ints.compare(dims.length, rhs.dims.length);
      }

      int index = 0;
      while (retVal == 0 && index < dims.length) {
        String[] lhsVals = dims[index];
        String[] rhsVals = rhs.dims[index];

        if (lhsVals == null) {
          if (rhsVals == null) {
            ++index;
            continue;
          }
          return -1;
        }

        if (rhsVals == null) {
          return 1;
        }

        retVal = Ints.compare(lhsVals.length, rhsVals.length);

        int valsIndex = 0;
        while (retVal == 0 && valsIndex < lhsVals.length) {
          retVal = lhsVals[valsIndex].compareTo(rhsVals[valsIndex]);
          ++valsIndex;
        }
        ++index;
      }

      return retVal;
    }

    @Override
    public String toString()
    {
      return "TimeAndDims{" +
             "timestamp=" + new DateTime(timestamp) +
             ", dims=" + Lists.transform(
          Arrays.asList(dims), new Function<String[], Object>()
      {
        @Override
        public Object apply(@Nullable String[] input)
        {
          if (input == null || input.length == 0) {
            return Arrays.asList("null");
          }
          return Arrays.asList(input);
        }
      }
      ) +
             '}';
    }
  }

  class DimDim
  {
    private final Interner interner;
    private final Map<String, Integer> falseIds;
    private final Map<Integer, String> falseIdsReverse;
    private volatile String[] sortedVals = null;
    // size on MapDB is slow so maintain a count here
    private volatile int size = 0;

    public DimDim(String dimName)
    {
      if (useOffheap) {
        falseIds = db.createHashMap(dimName)
                     .keySerializer(Serializer.STRING)
                     .valueSerializer(Serializer.INTEGER)
                     .make();
        falseIdsReverse = db.createHashMap(dimName + "_inverse")
                            .keySerializer(Serializer.INTEGER)
                            .valueSerializer(Serializer.STRING)
                            .make();
        interner = new WeakInterner();
      } else {
        BiMap<String, Integer> biMap = Maps.synchronizedBiMap(HashBiMap.<String, Integer>create());
        falseIds = biMap;
        falseIdsReverse = biMap.inverse();
        interner = new StrongInterner();
      }
    }

    public String get(String value)
    {
      return interner.getCanonicalValue(value);
    }

    public int getId(String value)
    {
      return falseIds.get(value);
    }

    public String getValue(int id)
    {
      return falseIdsReverse.get(id);
    }

    public boolean contains(String value)
    {
      return falseIds.containsKey(value);
    }

    public int size()
    {
      return size;
    }

    public synchronized int add(String value)
    {
      int id = size++;
      falseIds.put(value, id);
      if (useOffheap) {
        // onheap implementation uses a Bimap.
        falseIdsReverse.put(id, value);
      }
      return id;
    }

    public int getSortedId(String value)
    {
      assertSorted();
      return Arrays.binarySearch(sortedVals, value);
    }

    public String getSortedValue(int index)
    {
      assertSorted();
      return sortedVals[index];
    }

    public void sort()
    {
      if (sortedVals == null) {
        sortedVals = new String[falseIds.size()];

        int index = 0;
        for (String value : falseIds.keySet()) {
          sortedVals[index++] = value;
        }
        Arrays.sort(sortedVals);
      }
    }

    private void assertSorted()
    {
      if (sortedVals == null) {
        throw new ISE("Call sort() before calling the getSorted* methods.");
      }
    }

    public boolean compareCannonicalValues(String s1, String s2)
    {
      return interner.compareCanonicalValues(s1, s2);
    }
  }

  private static interface Interner
  {
    public String getCanonicalValue(String str);

    public boolean compareCanonicalValues(String s1, String s2);
  }

  private static class WeakInterner implements Interner
  {
    private static final WeakHashMap<String, WeakReference<String>> cache =
        new WeakHashMap();

    @Override
    public String getCanonicalValue(String str)
    {
      final WeakReference<String> cached = cache.get(str);
      if (cached != null) {
        final String value = cached.get();
        if (value != null) {
          return value;
        }
      }
      cache.put(str, new WeakReference(str));
      return str;
    }

    @Override
    public boolean compareCanonicalValues(String s1, String s2)
    {
      return s1.equals(s2);
    }
  }

  private static class StrongInterner implements Interner
  {
    final Map<String, String> poorMansInterning = Maps.newConcurrentMap();

    @Override
    public String getCanonicalValue(String str)
    {
      String value = poorMansInterning.get(str);
      if (value != null) {
        return value;
      }
      poorMansInterning.put(str, str);
      return str;
    }

    @Override
    public boolean compareCanonicalValues(String s1, String s2)
    {
      return s1 == s2;
    }
  }

  public static class TimeAndDimsSerializer extends BTreeKeySerializer<TimeAndDims> implements Serializable
  {
    private final TimeAndDimsComparator comparator;
    private final transient IncrementalIndex incrementalIndex;

    TimeAndDimsSerializer(IncrementalIndex incrementalIndex)
    {
      this.comparator = new TimeAndDimsComparator();
      this.incrementalIndex = incrementalIndex;
    }

    @Override
    public void serialize(DataOutput out, int start, int end, Object[] keys) throws IOException
    {
      for (int i = start; i < end; i++) {
        TimeAndDims timeAndDim = (TimeAndDims) keys[i];
        out.writeLong(timeAndDim.timestamp);
        out.writeInt(timeAndDim.dims.length);
        int index = 0;
        for (String[] dims : timeAndDim.dims) {
          if (dims == null) {
            out.write(-1);
          } else {
            DimDim dimDim = incrementalIndex.getDimension(incrementalIndex.dimensions.get(index));
            out.writeInt(dims.length);
            for (String value : dims) {
              out.writeInt(dimDim.getId(value));
            }
          }
          index++;
        }
      }
    }

    @Override
    public Object[] deserialize(DataInput in, int start, int end, int size) throws IOException
    {
      Object[] ret = new Object[size];
      for (int i = start; i < end; i++) {
        final long timeStamp = in.readLong();
        final String[][] dims = new String[in.readInt()][];
        for (int k = 0; k < dims.length; k++) {
          int len = in.readInt();
          if (len != -1) {
            DimDim dimDim = incrementalIndex.getDimension(incrementalIndex.dimensions.get(k));
            String[] col = new String[len];
            for (int l = 0; l < col.length; l++) {
              col[l] = dimDim.get(dimDim.getValue(in.readInt()));
            }
            dims[k] = col;
          }
        }
        ret[i] = new TimeAndDims(timeStamp, dims);
      }
      return ret;
    }

    @Override
    public Comparator<TimeAndDims> getComparator()
    {
      return comparator;
    }
  }

  public static class TimeAndDimsComparator implements Comparator, Serializable
  {
    @Override
    public int compare(Object o1, Object o2)
    {
      return ((TimeAndDims) o1).compareTo((TimeAndDims) o2);
    }
  }
}
