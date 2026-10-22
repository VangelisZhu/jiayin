package com.eddyizm.tempus.jiayin.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;
import java.util.List;

@Dao public abstract class LocalDao {
    // Even four-byte UTF-8 code points remain well below a 2 MiB CursorWindow.
    public static final int CHUNK_CHARS = 32 * 1024;

    @Query("SELECT * FROM local_rows WHERE account=:account AND kind=:kind AND id=:id LIMIT 1")
    protected abstract LocalRow head(String account, String kind, String id);
    @Query("SELECT * FROM local_rows WHERE account=:account AND kind=:kind ORDER BY updated,id")
    protected abstract List<LocalRow> heads(String account, String kind);
    @Query("SELECT payload FROM local_payload_chunks WHERE account=:account AND kind=:kind AND id=:id ORDER BY part")
    protected abstract List<String> tail(String account, String kind, String id);
    @Insert(onConflict = OnConflictStrategy.REPLACE) protected abstract void putHead(LocalRow row);
    @Insert protected abstract void putChunk(LocalPayloadChunk chunk);

    @Transaction public LocalRow get(String account, String kind, String id) {
        return assemble(head(account, kind, id));
    }

    @Transaction public List<LocalRow> list(String account, String kind) {
        List<LocalRow> rows = heads(account, kind);
        for (LocalRow row : rows) assemble(row);
        return rows;
    }

    private LocalRow assemble(LocalRow row) {
        if (row == null) return null;
        // Small listening/settings rows never have a continuation. Avoid an N+1 query per row.
        if (row.payload.length() < CHUNK_CHARS - 1) return row;
        List<String> chunks = tail(row.account, row.kind, row.id);
        if (!chunks.isEmpty()) {
            StringBuilder payload = new StringBuilder(row.payload);
            for (String chunk : chunks) payload.append(chunk);
            row.payload = payload.toString();
        }
        return row;
    }

    /** A document and all its chunks are replaced atomically, including shorter replacements. */
    @Transaction public void put(LocalRow row) {
        delete(row.account, row.kind, row.id);
        int end = chunkEnd(row.payload, 0);
        LocalRow first = new LocalRow();
        first.account = row.account; first.kind = row.kind; first.id = row.id;
        first.updated = row.updated; first.payload = row.payload.substring(0, end);
        putHead(first);
        int part = 1;
        while (end < row.payload.length()) {
            int start = end;
            end = chunkEnd(row.payload, start);
            LocalPayloadChunk chunk = new LocalPayloadChunk();
            chunk.account = row.account; chunk.kind = row.kind; chunk.id = row.id;
            chunk.part = part++; chunk.payload = row.payload.substring(start, end);
            putChunk(chunk);
        }
    }

    private static int chunkEnd(String payload, int start) {
        int end = Math.min(start + CHUNK_CHARS, payload.length());
        if (end < payload.length() && Character.isHighSurrogate(payload.charAt(end - 1))
                && Character.isLowSurrogate(payload.charAt(end))) end--;
        return end;
    }

    @Query("DELETE FROM local_rows WHERE account=:account AND kind=:kind AND id=:id")
    public abstract void delete(String account, String kind, String id);
    @Query("DELETE FROM local_rows WHERE account=:account AND kind=:kind")
    public abstract void clear(String account, String kind);
}
