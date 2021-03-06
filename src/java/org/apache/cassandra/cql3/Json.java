/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.cql3;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;

import org.apache.cassandra.config.CFMetaData;
import org.apache.cassandra.config.ColumnDefinition;
import org.apache.cassandra.cql3.functions.Function;
import org.apache.cassandra.db.marshal.UTF8Type;
import org.apache.cassandra.exceptions.InvalidRequestException;
import org.apache.cassandra.serializers.MarshalException;
import org.codehaus.jackson.io.JsonStringEncoder;
import org.codehaus.jackson.map.ObjectMapper;

/** Term-related classes for INSERT JSON support. */
public class Json
{
    public static final ObjectMapper JSON_OBJECT_MAPPER = new ObjectMapper();

    public static final JsonStringEncoder JSON_STRING_ENCODER = new JsonStringEncoder();

    public static final ColumnIdentifier JSON_COLUMN_ID = new ColumnIdentifier("[json]", true);

    public static Object decodeJson(String json)
    {
        try
        {
            return JSON_OBJECT_MAPPER.readValue(json, Object.class);
        }
        catch (IOException exc)
        {
            throw new MarshalException("Error decoding JSON string: " + exc.getMessage());
        }
    }

    public interface Raw
    {
        public Prepared prepareAndCollectMarkers(CFMetaData metadata, Collection<ColumnDefinition> receivers, VariableSpecifications boundNames);
    }

    /**
     * Represents a literal JSON string in an INSERT JSON statement.
     * For example: INSERT INTO mytable (key, col) JSON '{"key": 0, "col": 0}'; //不需要(key, col)
     */
    public static class Literal implements Raw
    {
        private final String text;

        public Literal(String text)
        {
            this.text = text;
        }

        public Prepared prepareAndCollectMarkers(CFMetaData metadata, Collection<ColumnDefinition> receivers, VariableSpecifications boundNames)
        {
            return new PreparedLiteral(parseJson(text, receivers));
        }
    }

    /**
     * Represents a marker for a JSON string in an INSERT JSON statement.
     * For example: INSERT INTO mytable (key, col) JSON ?;
     */
    public static class Marker implements Raw
    {
        protected final int bindIndex;

        public Marker(int bindIndex)
        {
            this.bindIndex = bindIndex;
        }

        public Prepared prepareAndCollectMarkers(CFMetaData metadata, Collection<ColumnDefinition> receivers, VariableSpecifications boundNames)
        {
            boundNames.add(bindIndex, makeReceiver(metadata));
            return new PreparedMarker(bindIndex, receivers);
        }

        private ColumnSpecification makeReceiver(CFMetaData metadata)
        {
            return new ColumnSpecification(metadata.ksName, metadata.cfName, JSON_COLUMN_ID, UTF8Type.instance);
        }
    }

    /**
     * A prepared, full set of JSON values.
     */
    public static abstract class Prepared
    {
        public abstract Term.Raw getRawTermForColumn(ColumnDefinition def);
    }

    /**
     * A prepared literal set of JSON values
     */
    private static class PreparedLiteral extends Prepared
    {
        private final Map<ColumnIdentifier, Term> columnMap;

        public PreparedLiteral(Map<ColumnIdentifier, Term> columnMap)
        {
            this.columnMap = columnMap;
        }

        public Term.Raw getRawTermForColumn(ColumnDefinition def)
        {
            Term value = columnMap.get(def.name);
            return value == null ? Constants.NULL_LITERAL : new ColumnValue(value);
        }
    }

    /**
     *  A prepared bind marker for a set of JSON values
     */
    private static class PreparedMarker extends Prepared
    {
        private final int bindIndex;
        private final Collection<ColumnDefinition> columns;

        private Map<ColumnIdentifier, Term> columnMap;

        public PreparedMarker(int bindIndex, Collection<ColumnDefinition> columns)
        {
            this.bindIndex = bindIndex;
            this.columns = columns;
        }

        public DelayedColumnValue getRawTermForColumn(ColumnDefinition def)
        {
            return new DelayedColumnValue(this, def);
        }

        public void bind(QueryOptions options) throws InvalidRequestException
        {
            // this will be called once per column, so avoid duplicating work
            if (columnMap != null)
                return;

            ByteBuffer value = options.getValues().get(bindIndex);
            if (value == null)
                throw new InvalidRequestException("Got null for INSERT JSON values");

            columnMap = parseJson(UTF8Type.instance.getSerializer().deserialize(value), columns);
        }

        public Term getValue(ColumnDefinition def)
        {
            return columnMap.get(def.name);
        }
    }

    /**
     * A Terminal for a single column.
     *
     * Note that this is intrinsically an already prepared term, but this still implements Term.Raw so that we can
     * easily use it to create raw operations.
     */
    private static class ColumnValue implements Term.Raw
    {
        private final Term term;

        public ColumnValue(Term term)
        {
            this.term = term;
        }

        @Override
        public Term prepare(String keyspace, ColumnSpecification receiver) throws InvalidRequestException
        {
            return term;
        }

        @Override
        public TestResult testAssignment(String keyspace, ColumnSpecification receiver)
        {
            return TestResult.NOT_ASSIGNABLE;
        }
    }

    /**
     * A NonTerminal for a single column.
     *
     * As with {@code ColumnValue}, this is intrinsically a prepared term but implements Terms.Raw for convenience.
     */
    private static class DelayedColumnValue extends Term.NonTerminal implements Term.Raw
    {
        private final PreparedMarker marker;
        private final ColumnDefinition column;

        public DelayedColumnValue(PreparedMarker prepared, ColumnDefinition column)
        {
            this.marker = prepared;
            this.column = column;
        }

        @Override
        public Term prepare(String keyspace, ColumnSpecification receiver) throws InvalidRequestException
        {
            return this;
        }

        @Override
        public TestResult testAssignment(String keyspace, ColumnSpecification receiver)
        {
            return TestResult.WEAKLY_ASSIGNABLE;
        }

        @Override
        public void collectMarkerSpecification(VariableSpecifications boundNames)
        {
            // We've already collected what we should (and in practice this method is never called).
        }

        @Override
        public boolean containsBindMarker()
        {
            return true;
        }

        @Override
        public Terminal bind(QueryOptions options) throws InvalidRequestException
        {
            marker.bind(options);
            Term term = marker.getValue(column);
            return term == null ? null : term.bind(options);
        }

        @Override
        public Iterable<Function> getFunctions()
        {
            return Collections.emptyList();
        }
    }

    /**
     * Given a JSON string, return a map of columns to their values for the insert.
     */
    private static Map<ColumnIdentifier, Term> parseJson(String jsonString, Collection<ColumnDefinition> expectedReceivers)
    {
        try
        {
            Map<String, Object> valueMap = JSON_OBJECT_MAPPER.readValue(jsonString, Map.class);

            if (valueMap == null)
                throw new InvalidRequestException("Got null for INSERT JSON values");

            handleCaseSensitivity(valueMap);

            Map<ColumnIdentifier, Term> columnMap = new HashMap<>(expectedReceivers.size());
            for (ColumnSpecification spec : expectedReceivers)
            {
                Object parsedJsonObject = valueMap.remove(spec.name.toString());
                if (parsedJsonObject == null)
                {
                    columnMap.put(spec.name, null);
                }
                else
                {
                    try
                    {
                        columnMap.put(spec.name, spec.type.fromJSONObject(parsedJsonObject));
                    }
                    catch(MarshalException exc)
                    {
                        throw new InvalidRequestException(String.format("Error decoding JSON value for %s: %s", spec.name, exc.getMessage()));
                    }
                }
            }

            if (!valueMap.isEmpty())
            {
                throw new InvalidRequestException(String.format(
                        "JSON values map contains unrecognized column: %s", valueMap.keySet().iterator().next()));
            }

            return columnMap;
        }
        catch (IOException exc)
        {
            throw new InvalidRequestException(String.format("Could not decode JSON string as a map: %s. (String was: %s)", exc.toString(), jsonString));
        }
        catch (MarshalException exc)
        {
            throw new InvalidRequestException(exc.getMessage());
        }
    }

    /**
     * Handles unquoting and case-insensitivity in map keys.
     */
    public static void handleCaseSensitivity(Map<String, Object> valueMap)
    {
        for (String mapKey : new ArrayList<>(valueMap.keySet()))
        {
            // if it's surrounded by quotes, remove them and preserve the case
            if (mapKey.startsWith("\"") && mapKey.endsWith("\""))
            {
                valueMap.put(mapKey.substring(1, mapKey.length() - 1), valueMap.remove(mapKey));
                continue;
            }

            // otherwise, lowercase it if needed
            String lowered = mapKey.toLowerCase(Locale.US);
            if (!mapKey.equals(lowered))
                valueMap.put(lowered, valueMap.remove(mapKey));
        }
    }
}
