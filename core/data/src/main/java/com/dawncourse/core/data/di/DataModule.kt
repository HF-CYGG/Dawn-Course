package com.dawncourse.core.data.di

import android.content.Context
import androidx.room.Room
import com.dawncourse.core.data.local.AppDatabase
import com.dawncourse.core.data.local.dao.CourseDao
import com.dawncourse.core.data.repository.CourseRepositoryImpl
import com.dawncourse.core.domain.repository.CourseRepository
import com.dawncourse.core.data.repository.SettingsRepositoryImpl
import com.dawncourse.core.domain.repository.SettingsRepository
import com.dawncourse.core.data.local.dao.SemesterDao
import com.dawncourse.core.data.repository.SemesterRepositoryImpl
import com.dawncourse.core.domain.repository.SemesterRepository
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.Binds
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 数据库依赖注入模块 (Hilt Module)
 *
 * 负责提供数据库实例和 DAO 的依赖注入。
 * 安装在 [SingletonComponent] 中，意味着这些对象在整个应用生命周期内是单例的。
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    
    /**
     * 提供 [AppDatabase] 实例
     *
     * @param context 应用上下文
     * @return 构建好的 Room 数据库实例，名为 "dawn_course.db"
     */
    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add originId column
                db.execSQL("ALTER TABLE courses ADD COLUMN originId INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE courses SET originId = id")
                
                // Add isModified column
                db.execSQL("ALTER TABLE courses ADD COLUMN isModified INTEGER NOT NULL DEFAULT 0")
                
                // Add note column
                db.execSQL("ALTER TABLE courses ADD COLUMN note TEXT NOT NULL DEFAULT ''")
            }
        }

        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "dawn_course.db"
        )
        .addMigrations(MIGRATION_4_5)
        .fallbackToDestructiveMigration()
        .build()
    }

    /**
     * 提供 [CourseDao] 实例
     *
     * @param database 数据库实例
     * @return 从数据库中获取的 DAO 对象
     */
    @Provides
    @Singleton
    fun provideCourseDao(database: AppDatabase): CourseDao {
        return database.courseDao()
    }

    @Provides
    @Singleton
    fun provideSemesterDao(database: AppDatabase): SemesterDao {
        return database.semesterDao()
    }
}

/**
 * 仓库依赖注入模块 (Hilt Module)
 *
 * 负责绑定 Repository 接口与其实现类。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    
    /**
     * 绑定 [CourseRepository] 接口到 [CourseRepositoryImpl] 实现
     *
     * 当需要注入 CourseRepository 时，Hilt 会自动提供 CourseRepositoryImpl 的实例。
     */
    @Binds
    @Singleton
    abstract fun bindCourseRepository(
        impl: CourseRepositoryImpl
    ): CourseRepository

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(
        impl: SettingsRepositoryImpl
    ): SettingsRepository

    @Binds
    @Singleton
    abstract fun bindSemesterRepository(
        impl: SemesterRepositoryImpl
    ): SemesterRepository
}
