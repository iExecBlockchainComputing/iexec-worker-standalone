/*
 * Copyright 2021 IEXEC BLOCKCHAIN TECH
 *
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

package com.iexec.standalone.configuration;

import com.github.cloudyrock.mongock.driver.mongodb.springdata.v2.decorator.impl.MongockTemplate;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class ConfigurationRepositoryMigrationTest {

    @Mock
    private MongoCollection<Document> collection;

    @Mock
    private MongoDatabase db;

    @Mock
    private FindIterable<Document> findIterable;

    @Mock
    private MongockTemplate mongockTemplate;

    @Mock
    private ReplayConfigurationRepository replayConfigurationRepository;

    @BeforeEach
    void init() {
        MockitoAnnotations.openMocks(this);
    }


    @Test
    void shouldMoveFromReplayField() {
        when(replayConfigurationRepository.count()).thenReturn(0L);
        Document document = new Document("firstKey", "firstValue");
        document.put("fromReplay", "132");
        mockFindFirstConfiguration(document);

        boolean isUpdated = new ConfigurationRepositoryMigration()
                .moveFromReplayField(mongockTemplate, replayConfigurationRepository);
        Assertions.assertTrue(isUpdated);
    }

    private void mockFindFirstConfiguration(Document document) {
        when(mongockTemplate.getDb()).thenReturn(db);
        when(db.getCollection(anyString())).thenReturn(collection);
        when(collection.find()).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(document);
    }

    @Test
    void shouldNotMoveFromReplayFieldSinceUpToDate() {
        when(replayConfigurationRepository.count()).thenReturn(1L);

        boolean isUpdated = new ConfigurationRepositoryMigration()
                .moveFromReplayField(mongockTemplate, replayConfigurationRepository);
        Assertions.assertFalse(isUpdated);
    }

    @Test
    void shouldNotMoveFromReplayFieldSinceMissingFieldInLegacy() {
        when(replayConfigurationRepository.count()).thenReturn(0L);
        Document document = new Document("firstKey", "firstValue");
        mockFindFirstConfiguration(document);

        boolean isUpdated = new ConfigurationRepositoryMigration()
                .moveFromReplayField(mongockTemplate, replayConfigurationRepository);
        Assertions.assertFalse(isUpdated);
    }

}
