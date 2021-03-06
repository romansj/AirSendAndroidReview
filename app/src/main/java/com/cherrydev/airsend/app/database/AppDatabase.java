package com.cherrydev.airsend.app.database;

import androidx.room.Database;
import androidx.room.RoomDatabase;

import com.cherrydev.airsend.app.database.models.Device;
import com.cherrydev.airsend.app.database.models.SentMessage;
import com.cherrydev.airsend.app.database.models.UserMessage;

@Database(entities = {Device.class, UserMessage.class, SentMessage.class}, version = 7, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {
    public abstract DbDao dao();
}
