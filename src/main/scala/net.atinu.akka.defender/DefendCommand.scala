package net.atinu.akka.defender

trait DefaultCommandNaming[T] extends NamedCommand[T] {
  override val cmdKey = DefendCommandKey(buildNameFromClass)

  private def buildNameFromClass =
    this.getClass.getSimpleName
}

abstract class DefendCommand[T] extends DefendExecution[T] with DefaultCommandNaming[T] {


}

abstract class SyncDefendCommand[T] extends SyncDefendExecution[T] with DefaultCommandNaming[T] {

}
