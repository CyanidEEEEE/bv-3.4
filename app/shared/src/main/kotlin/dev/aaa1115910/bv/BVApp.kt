package dev.aaa1115910.bv

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import de.schnettler.datastore.manager.DataStoreManager
import dev.aaa1115910.biliapi.http.BiliHttpProxyApi
import dev.aaa1115910.biliapi.repositories.*
import dev.aaa1115910.bv.dao.AppDatabase
import dev.aaa1115910.bv.entity.AuthData
import dev.aaa1115910.bv.entity.db.UserDB
import dev.aaa1115910.bv.network.HttpServer
import dev.aaa1115910.bv.repository.UserRepository
import dev.aaa1115910.bv.repository.VideoInfoRepository
import dev.aaa1115910.bv.util.*
import dev.aaa1115910.bv.viewmodel.*
import dev.aaa1115910.bv.viewmodel.home.DynamicViewModel
import dev.aaa1115910.bv.viewmodel.home.PopularViewModel
import dev.aaa1115910.bv.viewmodel.home.RecommendViewModel
import dev.aaa1115910.bv.viewmodel.index.PgcIndexViewModel
import dev.aaa1115910.bv.viewmodel.login.AppQrLoginViewModel
import dev.aaa1115910.bv.viewmodel.login.SmsLoginViewModel
import dev.aaa1115910.bv.viewmodel.pgc.*
import dev.aaa1115910.bv.viewmodel.search.SearchInputViewModel
import dev.aaa1115910.bv.viewmodel.search.SearchResultViewModel
import dev.aaa1115910.bv.viewmodel.user.*
import dev.aaa1115910.bv.viewmodel.video.VideoDetailViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.logger.Level
import org.koin.dsl.module
import org.slf4j.impl.HandroidLoggerAdapter

class BVApp : Application() {
    companion object {
        @SuppressLint("StaticFieldLeak")
        lateinit var context: Context
        lateinit var dataStoreManager: DataStoreManager
        lateinit var koinApplication: KoinApplication
        var instance: BVApp? = null

        fun getAppDatabase(context: Context = this.context) = AppDatabase.getDatabase(context)
    }

    override fun onCreate() {
        super.onCreate()
        context = this.applicationContext
        HandroidLoggerAdapter.DEBUG = BuildConfig.DEBUG
        dataStoreManager = DataStoreManager(applicationContext.dataStore)
        
        // 注册Activity生命周期回调，自动设置窗口大小
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                // 为所有Activity设置窗口大小为右侧3/4
                setActivityWindowSize(activity)
            }
            
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        
        if (Prefs.blacklistUser) {
            R.string.blacklist_user_toast.toast(context)
            return
        }
        koinApplication = startKoin {
            androidLogger(if (BuildConfig.DEBUG) Level.ERROR else Level.NONE)
            androidContext(this@BVApp)
            modules(appModule)
        }
        FirebaseUtil.init(applicationContext)
        LogCatcherUtil.installLogCatcher()
        initRepository()
        initProxy()
        instance = this
        updateMigration()
        HttpServer.startServer()
        updateBlacklist()
    }

    /**
     * 设置Activity窗口大小为右侧3/4区域
     */
    private fun setActivityWindowSize(activity: Activity) {
        try {
            val window = activity.window
            val params = window.attributes
            
            // 获取屏幕尺寸
            val displayMetrics = resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels
            
            // 设置窗口宽度为屏幕的3/4，高度为全屏
            params.width = (screenWidth * 0.75).toInt()
            params.height = screenHeight
            
            // 设置窗口位置为右侧
            params.gravity = Gravity.END or Gravity.TOP
            
            // 应用设置
            window.attributes = params
        } catch (e: Exception) {
            // 静默处理异常，避免影响应用运行
        }
    }

    fun initRepository() {
        val channelRepository by koinApplication.koin.inject<ChannelRepository>()
        channelRepository.initDefaultChannel(Prefs.accessToken, Prefs.buvid)

        val authRepository by koinApplication.koin.inject<AuthRepository>()
        authRepository.sessionData = Prefs.sessData.takeIf { it.isNotEmpty() }
        authRepository.biliJct = Prefs.biliJct.takeIf { it.isNotEmpty() }
        authRepository.accessToken = Prefs.accessToken.takeIf { it.isNotEmpty() }
        authRepository.mid = Prefs.uid.takeIf { it != 0L }
        authRepository.buvid3 = Prefs.buvid3
        authRepository.buvid = Prefs.buvid
    }

    fun initProxy() {
        if (Prefs.enableProxy) {
            BiliHttpProxyApi.createClient(Prefs.proxyHttpServer)

            val channelRepository by koinApplication.koin.inject<ChannelRepository>()
            runCatching {
                channelRepository.initProxyChannel(
                    Prefs.accessToken,
                    Prefs.buvid,
                    Prefs.proxyGRPCServer
                )
            }
        }
    }

    private fun updateMigration() {
        val lastVersionCode = Prefs.lastVersionCode
        if (lastVersionCode >= BuildConfig.VERSION_CODE) return
        if (lastVersionCode < 576) {
            // 从 Prefs 中读取登录数据写入 UserDB
            if (Prefs.isLogin) {
                runBlocking {
                    val existedUser = getAppDatabase().userDao().findUserByUid(Prefs.uid)
                    if (existedUser == null) {
                        val user = UserDB(
                            uid = Prefs.uid,
                            username = "Unknown",
                            avatar = "",
                            auth = AuthData.fromPrefs().toJson()
                        )
                        getAppDatabase().userDao().insert(user)
                    }
                }
            }
        }
        Prefs.lastVersionCode = BuildConfig.VERSION_CODE
    }

    private fun updateBlacklist() {
        CoroutineScope(Dispatchers.IO).launch {
            BlacklistUtil.updateBlacklist(context)
            BlacklistUtil.checkUid(Prefs.uid)
        }
    }
}

val appModule = module {
    single { AuthRepository() }
    single { UserRepository(get()) }
    single { LoginRepository() }
    single { VideoInfoRepository() }
    single { ChannelRepository() }
    single { FavoriteRepository(get()) }
    single { LikeRepository(get()) }
    single { CoinRepository(get())}
    single { OneClickTripleActionRepository(get()) }
    single { HistoryRepository(get(), get()) }
    single { ToViewRepository(get(), get()) }
    single { SearchRepository(get(), get()) }
    single { VideoPlayRepository(get(), get()) }
    single { RecommendVideoRepository(get(), get()) }
    single { VideoDetailRepository(get(), get(), get(), get(), get()) }
    single { SeasonRepository(get()) }
    single { dev.aaa1115910.biliapi.repositories.UserRepository(get(), get()) }
    single { PgcRepository() }
    single { UgcRepository(get()) }
    single { CommentRepository(get(), get()) }
    viewModel { DynamicViewModel(get(), get()) }
    viewModel { RecommendViewModel(get()) }
    viewModel { PopularViewModel(get()) }
    viewModel { AppQrLoginViewModel(get(), get()) }
    viewModel { SmsLoginViewModel(get(), get()) }
    viewModel { UserViewModel(get()) }
    viewModel { HistoryViewModel(get(), get()) }
    viewModel { ToViewViewModel(get(), get()) }
    viewModel { FavoriteViewModel(get()) }
    viewModel { UserSpaceViewModel(get()) }
    viewModel { FollowViewModel(get()) }
    viewModel { SearchInputViewModel(get()) }
    viewModel { SearchResultViewModel(get()) }
    viewModel { FollowingSeasonViewModel(get()) }
    viewModel { TagViewModel() }
    viewModel { VideoPlayerV3ViewModel(get(), get()) }
    viewModel { VideoDetailViewModel(get(), get()) }
    viewModel { UserSwitchViewModel(get()) }
    viewModel { PgcIndexViewModel(get()) }
    viewModel { PgcAnimeViewModel(get()) }
    viewModel { PgcGuoChuangViewModel(get()) }
    viewModel { PgcDocumentaryViewModel(get()) }
    viewModel { PgcMovieViewModel(get()) }
    viewModel { PgcTvViewModel(get()) }
    viewModel { PgcVarietyViewModel(get()) }
    viewModel { CommentViewModel(get()) }
    viewModel { DynamicDetailViewModel(get()) }
    viewModel { SeasonViewModel(get(), get()) }
}

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "Settings")
