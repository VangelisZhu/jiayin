package com.eddyizm.tempus.jiayin.data;

import androidx.annotation.NonNull;
import androidx.room.Entity;

@Entity(tableName = "local_rows", primaryKeys = {"account", "kind", "id"})
public class LocalRow {
    @NonNull public String account = "";
    @NonNull public String kind = "";
    @NonNull public String id = "";
    @NonNull public String payload = "";
    public long updated;
    public LocalRow() {}
}
