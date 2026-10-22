package com.eddyizm.tempus.jiayin.data;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.ForeignKey;

/** Bounded continuation of a local JSON document; deleted with its parent row. */
@Entity(tableName = "local_payload_chunks", primaryKeys = {"account", "kind", "id", "part"},
        foreignKeys = @ForeignKey(entity = LocalRow.class,
                parentColumns = {"account", "kind", "id"},
                childColumns = {"account", "kind", "id"}, onDelete = ForeignKey.CASCADE))
public class LocalPayloadChunk {
    @NonNull public String account = "";
    @NonNull public String kind = "";
    @NonNull public String id = "";
    public int part;
    @NonNull public String payload = "";
}
