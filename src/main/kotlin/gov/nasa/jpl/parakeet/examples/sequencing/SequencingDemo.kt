package gov.nasa.jpl.parakeet.examples.sequencing

import gov.nasa.jpl.parakeet.examples.sequencing.activities.ActivateSequence
import gov.nasa.jpl.parakeet.examples.sequencing.activities.LoadSequence
import gov.nasa.jpl.parakeet.examples.sequencing.activities.UnloadSequence
import gov.nasa.jpl.parakeet.examples.sequencing.commands.ALL_MODELED_COMMANDS
import gov.nasa.jpl.parakeet.examples.sequencing.fsw.FswModel
import gov.nasa.jpl.parakeet.examples.sequencing.sequence_engine.SequencingModel
import gov.nasa.jpl.parakeet.examples.sequencing.telecom.TelecomModel
import gov.nasa.jpl.parakeet.foundation.plans.activities
import gov.nasa.jpl.parakeet.foundation.serialization.ResultSerializer
import gov.nasa.jpl.parakeet.foundation.tasks.InitScope
import gov.nasa.jpl.parakeet.foundation.tasks.InitScope.Companion.subContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import java.nio.file.Path

class SequencingDemo(
    val rootDir: Path,
    context: InitScope,
) {
    val fsw: FswModel
    val telecom: TelecomModel
    val sequencing: SequencingModel

    init {
        with (context) {
            fsw = FswModel(subContext("fsw"))
            telecom = TelecomModel(subContext("telecom"))
            sequencing = SequencingModel(
                ALL_MODELED_COMMANDS.modeledCommands(this@SequencingDemo),
                this@SequencingDemo,
                subContext("sequencing"),
            )
        }
    }

    companion object {
        val JSON_FORMAT = Json {
            serializersModule = SerializersModule {
                contextual(Result::class) { ResultSerializer(it[0]) }
                activities {
                    // Planning activities
                    activity(LoadSequence::class)
                    activity(ActivateSequence::class)
                    activity(UnloadSequence::class)

                    // Include modeled commands
                    ALL_MODELED_COMMANDS.includeModeledCommands()
                }
            }
        }
    }
}