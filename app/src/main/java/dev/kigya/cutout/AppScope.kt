package dev.kigya.cutout

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MainImmediate

@Module
@InstallIn(SingletonComponent::class)
object CoroutineModule {
    @Provides
    @MainImmediate
    fun mainImmediate(): CoroutineDispatcher = Dispatchers.Main.immediate
}

/** Process-wide scope for work that must finish after a service stops. */
@Singleton
class AppScope @Inject constructor(
    @MainImmediate dispatcher: CoroutineDispatcher,
) : CoroutineScope {
    private val job = SupervisorJob()
    override val coroutineContext = job + dispatcher
}
