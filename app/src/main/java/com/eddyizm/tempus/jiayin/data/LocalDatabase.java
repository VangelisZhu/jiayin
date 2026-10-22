package com.eddyizm.tempus.jiayin.data;

import android.content.Context;
import android.database.Cursor;
import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

/** Separate versioned Room database: never alters Tempus' download index or NAS DB. */
@Database(entities = {LocalRow.class, LocalPayloadChunk.class}, version = 2, exportSchema = true)
public abstract class LocalDatabase extends RoomDatabase {
    public abstract LocalDao rows();
    public static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS local_payload_chunks (account TEXT NOT NULL, kind TEXT NOT NULL, id TEXT NOT NULL, part INTEGER NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(account, kind, id, part), FOREIGN KEY(account, kind, id) REFERENCES local_rows(account, kind, id) ON UPDATE NO ACTION ON DELETE CASCADE)");
            long longest;
            // Never SELECT an old payload into a Cursor: affected installs already contain huge rows.
            try (Cursor cursor = db.query("SELECT coalesce(max(length(payload)), 0) FROM local_rows")) {
                cursor.moveToFirst(); longest = cursor.getLong(0);
            }
            int part = 1;
            for (long offset = LocalDao.CHUNK_CHARS; offset < longest; offset += LocalDao.CHUNK_CHARS) {
                db.execSQL("INSERT INTO local_payload_chunks(account, kind, id, part, payload) SELECT account, kind, id, ?, substr(payload, ?, ? ) FROM local_rows WHERE length(payload) > ?",
                        new Object[]{part++, offset + 1, LocalDao.CHUNK_CHARS, offset});
            }
            db.execSQL("UPDATE local_rows SET payload = substr(payload, 1, ?) WHERE length(payload) > ?",
                    new Object[]{LocalDao.CHUNK_CHARS, LocalDao.CHUNK_CHARS});
        }
    };
    private static volatile LocalDatabase instance;
    public static LocalDatabase get(Context context) {
        if (instance == null) synchronized (LocalDatabase.class) {
            if (instance == null) instance = Room.databaseBuilder(context.getApplicationContext(), LocalDatabase.class, "jiayin-local.db")
                    .addMigrations(MIGRATION_1_2).build();
        }
        return instance;
    }
}
