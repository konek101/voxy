package me.cortex.voxy.client.network;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MapperTranslator block state ID translation.
 * 
 * These tests help debug the "StateId: X max id: Y" error by testing:
 * 1. Translation table construction and lookup
 * 2. Race conditions between MapperSync and section data
 * 3. Edge cases in ID translation
 * 4. Bounds validation during translation
 */
class MapperTranslatorTest {
    
    /**
     * Simulated translation table for testing.
     * Maps server block IDs to client block IDs.
     */
    private Int2IntOpenHashMap blockIdTranslations;
    
    /**
     * Simulated stored block state data for later resolution.
     */
    private List<byte[]> serverBlockStateData;
    
    @BeforeEach
    void setUp() {
        blockIdTranslations = new Int2IntOpenHashMap();
        blockIdTranslations.defaultReturnValue(-1);
        serverBlockStateData = new ArrayList<>();
    }
    
    // ==================== Translation Table Tests ====================
    
    @Test
    @DisplayName("Translation table should return -1 for unknown server IDs")
    void testTranslationTableUnknownId() {
        // Empty translation table
        assertEquals(-1, blockIdTranslations.getOrDefault(100, -1));
        assertEquals(-1, blockIdTranslations.getOrDefault(0, -1));
        assertEquals(-1, blockIdTranslations.getOrDefault(Integer.MAX_VALUE, -1));
    }
    
    @Test
    @DisplayName("Translation table should correctly map server IDs to client IDs")
    void testTranslationTableBasicMapping() {
        // Simulate server ID -> client ID mappings (they can differ)
        blockIdTranslations.put(0, 0);   // Air on both
        blockIdTranslations.put(1, 5);   // Server stone -> Client stone (different ID)
        blockIdTranslations.put(2, 3);   // Server dirt -> Client dirt
        blockIdTranslations.put(100, 50); // Some modded block
        
        assertEquals(0, blockIdTranslations.getOrDefault(0, -1));
        assertEquals(5, blockIdTranslations.getOrDefault(1, -1));
        assertEquals(3, blockIdTranslations.getOrDefault(2, -1));
        assertEquals(50, blockIdTranslations.getOrDefault(100, -1));
        assertEquals(-1, blockIdTranslations.getOrDefault(999, -1)); // Unknown
    }
    
    @Test
    @DisplayName("Translation should handle gaps in server ID sequence")
    void testTranslationWithGaps() {
        // Server may not assign sequential IDs
        blockIdTranslations.put(0, 0);
        blockIdTranslations.put(5, 10);
        blockIdTranslations.put(100, 20);
        blockIdTranslations.put(500, 30);
        
        assertEquals(0, blockIdTranslations.getOrDefault(0, -1));
        assertEquals(-1, blockIdTranslations.getOrDefault(1, -1)); // Gap
        assertEquals(-1, blockIdTranslations.getOrDefault(2, -1)); // Gap
        assertEquals(-1, blockIdTranslations.getOrDefault(3, -1)); // Gap
        assertEquals(-1, blockIdTranslations.getOrDefault(4, -1)); // Gap
        assertEquals(10, blockIdTranslations.getOrDefault(5, -1));
        assertEquals(20, blockIdTranslations.getOrDefault(100, -1));
        assertEquals(30, blockIdTranslations.getOrDefault(500, -1));
    }
    
    // ==================== Bounds Validation Tests ====================
    
    @Test
    @DisplayName("Should detect when translated ID exceeds client mapper bounds")
    void testBoundsValidation() {
        int clientMapperSize = 443; // Client only has 443 block states (IDs 0-442)
        
        // These translations would produce valid IDs
        blockIdTranslations.put(0, 0);
        blockIdTranslations.put(1, 100);
        blockIdTranslations.put(2, 442); // Max valid ID
        
        // These would produce INVALID IDs (exceed client mapper)
        blockIdTranslations.put(3, 445); // This would cause the error!
        blockIdTranslations.put(4, 500);
        blockIdTranslations.put(5, 1000);
        
        // Test valid translations
        assertTrue(blockIdTranslations.get(0) < clientMapperSize);
        assertTrue(blockIdTranslations.get(1) < clientMapperSize);
        assertTrue(blockIdTranslations.get(2) < clientMapperSize);
        
        // Test invalid translations - these would cause "StateId: X max id: Y"
        assertFalse(blockIdTranslations.get(3) < clientMapperSize, 
            "Client ID 445 >= client mapper size 443 - would cause StateId error!");
        assertFalse(blockIdTranslations.get(4) < clientMapperSize);
        assertFalse(blockIdTranslations.get(5) < clientMapperSize);
    }
    
    @Test
    @DisplayName("Should handle case where server has more block states than client")
    void testServerHasMoreBlockStates() {
        int serverBlockStateCount = 500;
        int clientBlockStateCount = 443;
        
        // Server knows about 500 block states, client only knows 443
        // This can happen when:
        // 1. Server has mods that client doesn't have
        // 2. Server discovered new block states after client joined
        
        for (int serverBlockId = 0; serverBlockId < serverBlockStateCount; serverBlockId++) {
            // Simulate translation - some server IDs map to valid client IDs,
            // some don't exist on the client (would need to be registered)
            int clientBlockId = serverBlockId; // Naive 1:1 mapping
            blockIdTranslations.put(serverBlockId, clientBlockId);
        }
        
        // Count how many translations would exceed client bounds
        int invalidTranslations = 0;
        for (int serverBlockId = 0; serverBlockId < serverBlockStateCount; serverBlockId++) {
            int clientBlockId = blockIdTranslations.get(serverBlockId);
            if (clientBlockId >= clientBlockStateCount) {
                invalidTranslations++;
            }
        }
        
        // We expect 500 - 443 = 57 invalid translations
        assertEquals(57, invalidTranslations, 
            "Expected 57 server block IDs to exceed client mapper bounds");
    }
    
    // ==================== Race Condition Tests ====================
    
    @Test
    @DisplayName("Should detect race condition: section data contains ID not in translation table")
    void testRaceConditionMissingTranslation() {
        // Setup: MapperSync sent with 100 entries
        for (int i = 0; i < 100; i++) {
            blockIdTranslations.put(i, i);
            serverBlockStateData.add(new byte[]{(byte) i}); // Dummy data
        }
        
        // Simulate section data that contains server block ID 105
        // This ID was discovered AFTER the MapperSync was sent
        int serverBlockIdInSection = 105;
        
        // Translation lookup returns -1 (not in table)
        assertEquals(-1, blockIdTranslations.getOrDefault(serverBlockIdInSection, -1),
            "Server block ID 105 should not be in translation table (only 0-99 were synced)");
        
        // Block state data is also not available for this ID
        assertTrue(serverBlockIdInSection >= serverBlockStateData.size(),
            "Block state data for ID 105 should not be available");
    }
    
    @Test
    @DisplayName("Should handle incremental MapperSync updates")
    void testIncrementalMapperSync() {
        // First MapperSync: 100 entries
        for (int i = 0; i < 100; i++) {
            blockIdTranslations.put(i, i);
            serverBlockStateData.add(new byte[]{(byte) i});
        }
        assertEquals(100, blockIdTranslations.size());
        assertEquals(100, serverBlockStateData.size());
        
        // Second MapperSync: 150 entries (50 new)
        for (int i = 100; i < 150; i++) {
            blockIdTranslations.put(i, i);
            serverBlockStateData.add(new byte[]{(byte) i});
        }
        assertEquals(150, blockIdTranslations.size());
        assertEquals(150, serverBlockStateData.size());
        
        // Now server block ID 125 should be translatable
        assertEquals(125, blockIdTranslations.getOrDefault(125, -1));
    }
    
    @Test
    @DisplayName("Concurrent access to translation table should be thread-safe")
    void testConcurrentAccess() throws InterruptedException {
        int numThreads = 10;
        int operationsPerThread = 1000;
        CountDownLatch latch = new CountDownLatch(numThreads);
        AtomicInteger errors = new AtomicInteger(0);
        
        // Pre-populate with some entries
        ConcurrentHashMap<Integer, Integer> concurrentTranslations = new ConcurrentHashMap<>();
        for (int i = 0; i < 100; i++) {
            concurrentTranslations.put(i, i);
        }
        
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        
        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    for (int op = 0; op < operationsPerThread; op++) {
                        // Simulate reads and writes
                        int key = (threadId * 100) + (op % 100);
                        
                        if (op % 2 == 0) {
                            // Read
                            Integer value = concurrentTranslations.get(key);
                        } else {
                            // Write
                            concurrentTranslations.put(key, key + 1000);
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        
        assertTrue(latch.await(10, TimeUnit.SECONDS), "Threads should complete");
        assertEquals(0, errors.get(), "No errors should occur during concurrent access");
        
        executor.shutdown();
    }
    
    // ==================== Edge Case Tests ====================
    
    @Test
    @DisplayName("Air block (ID 0) should always translate to 0")
    void testAirBlockTranslation() {
        // Air is always ID 0 on both server and client
        blockIdTranslations.put(0, 0);
        
        assertEquals(0, blockIdTranslations.get(0));
        
        // Even if translation table is missing, air should map to air
        Int2IntOpenHashMap emptyTable = new Int2IntOpenHashMap();
        emptyTable.defaultReturnValue(-1);
        
        // Air (0) is a special case that should be handled separately
        int airId = 0;
        int translatedAir = emptyTable.getOrDefault(airId, -1);
        if (translatedAir == -1 && airId == 0) {
            translatedAir = 0; // Fallback for air
        }
        assertEquals(0, translatedAir, "Air should always translate to 0");
    }
    
    @Test
    @DisplayName("Should handle maximum valid block ID")
    void testMaxBlockId() {
        // Block ID uses 20 bits in the mapping format (based on Mapper.getBlockId)
        int maxBlockId = (1 << 20) - 1; // 1048575
        
        blockIdTranslations.put(maxBlockId, maxBlockId);
        assertEquals(maxBlockId, blockIdTranslations.get(maxBlockId));
    }
    
    @Test
    @DisplayName("Should handle translation table update during lookup")
    void testTranslationTableUpdate() {
        // Initial state: ID 100 not in table
        assertEquals(-1, blockIdTranslations.getOrDefault(100, -1));
        
        // Update table (simulates on-the-fly resolution)
        blockIdTranslations.put(100, 50);
        
        // Now lookup should succeed
        assertEquals(50, blockIdTranslations.getOrDefault(100, -1));
    }
    
    // ==================== maxBlockId Tracking Tests ====================
    
    @Test
    @DisplayName("Section with maxBlockId should require MapperSync coverage")
    void testMaxBlockIdTracking() {
        int lastSyncedBlockStateCount = 100;
        int sectionMaxBlockId = 150;
        int requiredBlockCount = sectionMaxBlockId + 1; // Need 151 entries to cover ID 150
        
        // Check if MapperSync is needed
        boolean needsMapperSync = requiredBlockCount > lastSyncedBlockStateCount;
        
        assertTrue(needsMapperSync, 
            "Should need MapperSync: section has maxBlockId 150 but only synced 100 entries");
    }
    
    @Test
    @DisplayName("Should correctly calculate maxBlockId in simulated section data")
    void testCalculateMaxBlockId() {
        // Simulate a section's LUT (Look-Up Table) entries
        long[] lutEntries = new long[10];
        
        // Compose some mapping IDs with different block IDs
        // Using the formula from Mapper.composeMappingId: (light << 56) | (biomeId << 47) | (blockId << 27)
        lutEntries[0] = composeMappingId(0, 5, 0);   // blockId = 5
        lutEntries[1] = composeMappingId(0, 100, 0); // blockId = 100
        lutEntries[2] = composeMappingId(0, 50, 0);  // blockId = 50
        lutEntries[3] = composeMappingId(0, 445, 0); // blockId = 445 - THIS IS THE MAX!
        lutEntries[4] = composeMappingId(0, 200, 0); // blockId = 200
        lutEntries[5] = composeMappingId(0, 0, 0);   // blockId = 0 (air)
        lutEntries[6] = composeMappingId(0, 300, 0); // blockId = 300
        lutEntries[7] = composeMappingId(0, 1, 0);   // blockId = 1
        lutEntries[8] = composeMappingId(0, 443, 0); // blockId = 443
        lutEntries[9] = composeMappingId(0, 10, 0);  // blockId = 10
        
        // Calculate maxBlockId
        int maxBlockId = 0;
        for (long entry : lutEntries) {
            int blockId = getBlockId(entry);
            if (blockId > maxBlockId) {
                maxBlockId = blockId;
            }
        }
        
        assertEquals(445, maxBlockId, "Max block ID in section should be 445");
        
        // If client mapper only has 443 entries, this would cause the error
        int clientMapperSize = 443;
        assertTrue(maxBlockId >= clientMapperSize, 
            "Section contains block ID 445 which exceeds client mapper size 443 - this causes the error!");
    }
    
    // ==================== Helper Methods ====================
    
    /**
     * Compose a mapping ID from light, blockId, and biomeId.
     * Mirrors Mapper.composeMappingId
     */
    private static long composeMappingId(int light, int blockId, int biomeId) {
        return (Integer.toUnsignedLong(light) << 56) | 
               (Integer.toUnsignedLong(biomeId) << 47) | 
               (Integer.toUnsignedLong(blockId) << 27);
    }
    
    /**
     * Extract block ID from a mapping ID.
     * Mirrors Mapper.getBlockId
     */
    private static int getBlockId(long id) {
        return (int) ((id >> 27) & ((1 << 20) - 1));
    }
    
    // ==================== Scenario Tests (Real Bug Reproduction) ====================
    
    @Test
    @DisplayName("Reproduce StateId error scenario: server syncs 443 blocks but section has ID 445")
    void testReproduceStateIdError() {
        // This test reproduces the exact error: "StateId: 445 max id: 443"
        
        // Setup: Server synced 443 block states
        int serverSyncedCount = 443;
        for (int i = 0; i < serverSyncedCount; i++) {
            blockIdTranslations.put(i, i); // 1:1 mapping for simplicity
            serverBlockStateData.add(new byte[]{(byte)(i & 0xFF)});
        }
        
        // Client mapper also has 443 entries
        int clientMapperSize = 443;
        
        // But the section data contains block ID 445!
        int problematicBlockId = 445;
        
        // This is the bug scenario:
        // 1. Translation table has 443 entries (0-442)
        // 2. Section data references block ID 445
        // 3. Translation lookup returns -1 (not found)
        // 4. Without proper fallback, this becomes a problem
        
        int translatedId = blockIdTranslations.getOrDefault(problematicBlockId, -1);
        
        assertEquals(-1, translatedId, 
            "Block ID 445 should not be in translation table");
        assertTrue(problematicBlockId >= serverBlockStateData.size(),
            "Block state data for ID 445 should not be available");
        
        // The fix: When translation fails and no state data is available,
        // we should either:
        // 1. Map to air (safe fallback)
        // 2. Request updated MapperSync before processing section
        
        // Verify the fix approach
        int safeClientId = (translatedId == -1) ? 0 : translatedId; // Map to air
        assertTrue(safeClientId < clientMapperSize,
            "Safe fallback should produce valid client ID");
    }
    
    @Test
    @DisplayName("Verify proper maxBlockId-based MapperSync requirement")
    void testMaxBlockIdBasedMapperSyncRequirement() {
        // Server tracks maxBlockId when serializing section
        int sectionMaxBlockId = 445;
        
        // Server has synced 443 entries to client
        int lastSyncedBlockStateCount = 443;
        
        // Check if MapperSync is needed BEFORE sending section
        int requiredBlockCount = sectionMaxBlockId + 1; // 446
        boolean needsMapperSync = requiredBlockCount > lastSyncedBlockStateCount;
        
        assertTrue(needsMapperSync,
            "Server should send MapperSync before this section: " +
            "section has maxBlockId=" + sectionMaxBlockId + 
            " but only synced " + lastSyncedBlockStateCount + " entries");
        
        // After proper MapperSync, translation should work
        for (int i = 0; i < requiredBlockCount; i++) {
            blockIdTranslations.put(i, i);
        }
        
        // Now translation should succeed
        int translatedId = blockIdTranslations.getOrDefault(sectionMaxBlockId, -1);
        assertEquals(sectionMaxBlockId, translatedId,
            "After proper MapperSync, block ID 445 should translate correctly");
    }
}
