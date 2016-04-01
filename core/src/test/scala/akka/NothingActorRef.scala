package akka

import akka.actor._

object NothingActorRef extends MinimalActorRef {
  override val path: RootActorPath = new RootActorPath(Address("akka", "all-systems"), "/Nobody")
  override def provider = throw new UnsupportedOperationException("Nobody does not provide")

  private val serialized = new SerializedNobody

  @throws(classOf[java.io.ObjectStreamException])
  override protected def writeReplace(): AnyRef = serialized
}
