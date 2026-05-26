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
package io.trino.plugin.mongodb;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.trino.spi.connector.SchemaTableName;
import io.trino.spi.type.Type;
import io.trino.spi.type.TypeManager;
import org.bson.Document;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

public class JsonSchemaOverrideLoader
{
    private static final String FIELDS_KEY = "fields";
    private static final String FIELDS_NAME_KEY = "name";
    private static final String FIELDS_TYPE_KEY = "type";
    private static final String FIELDS_HIDDEN_KEY = "hidden";
    private static final String COMMENT_KEY = "comment";

    private final Map<SchemaTableName, Document> schemaOverride;
    private final TypeManager typeManager;

    public JsonSchemaOverrideLoader(Path jsonSchemaFile, TypeManager typeManager)
            throws IOException
    {
        this.typeManager = requireNonNull(typeManager, "typeManager is null");
        requireNonNull(jsonSchemaFile, "jsonSchemaFile is null");

        if (!Files.exists(jsonSchemaFile)) {
            throw new IOException("JSON schema override file does not exist: " + jsonSchemaFile);
        }

        this.schemaOverride = loadSchemaOverride(jsonSchemaFile);
    }

    private Map<SchemaTableName, Document> loadSchemaOverride(Path jsonSchemaFile)
            throws IOException
    {
        String content = Files.readString(jsonSchemaFile);
        Document root = Document.parse(content);

        ImmutableMap.Builder<SchemaTableName, Document> builder = ImmutableMap.builder();
        for (String schemaName : root.keySet()) {
            Object schemaValue = root.get(schemaName);
            if (!(schemaValue instanceof Document schemaDoc)) {
                continue;
            }

            for (String tableName : schemaDoc.keySet()) {
                Object tableValue = schemaDoc.get(tableName);
                if (!(tableValue instanceof Document tableDoc)) {
                    continue;
                }

                SchemaTableName fullTableName = new SchemaTableName(schemaName.toLowerCase(Locale.ENGLISH), tableName.toLowerCase(Locale.ENGLISH));
                builder.put(fullTableName, tableDoc);
            }
        }

        return builder.buildOrThrow();
    }

    public boolean hasSchemaOverride(SchemaTableName tableName)
    {
        return schemaOverride.containsKey(tableName);
    }

    public Optional<Document> getTableSchema(SchemaTableName tableName)
    {
        return Optional.ofNullable(schemaOverride.get(tableName));
    }

    public List<MongoColumnHandle> getColumnHandles(SchemaTableName tableName, TypeManager typeManager)
    {
        Document tableDoc = schemaOverride.get(tableName);
        if (tableDoc == null) {
            return ImmutableList.of();
        }

        List<?> fields = tableDoc.get(FIELDS_KEY, List.class);
        if (fields == null) {
            return ImmutableList.of();
        }

        ImmutableList.Builder<MongoColumnHandle> columnHandles = ImmutableList.builder();
        for (Object field : fields) {
            if (!(field instanceof Document fieldDoc)) {
                continue;
            }

            String name = fieldDoc.getString(FIELDS_NAME_KEY);
            String typeString = fieldDoc.getString(FIELDS_TYPE_KEY);
            boolean hidden = fieldDoc.getBoolean(FIELDS_HIDDEN_KEY, false);
            String comment = fieldDoc.getString(COMMENT_KEY);

            if (name == null || typeString == null) {
                continue;
            }

            Type type = typeManager.fromSqlType(typeString);
            columnHandles.add(new MongoColumnHandle(name, ImmutableList.of(), type, hidden, false, Optional.ofNullable(comment)));
        }

        return columnHandles.build();
    }

    public Set<SchemaTableName> getTableNames()
    {
        return schemaOverride.keySet();
    }

    public Set<String> getSchemaNames()
    {
        return schemaOverride.keySet().stream()
                .map(SchemaTableName::getSchemaName)
                .collect(java.util.stream.Collectors.toSet());
    }
}
