package com.twitter.finagle.zookeeper

import com.google.common.collect.ImmutableSet
import com.twitter.common.net.pool.DynamicHostSet
import com.twitter.common.zookeeper.ServerSet
import com.twitter.common.zookeeper.ServerSetImpl
import com.twitter.finagle.stats.DefaultStatsReceiver
import com.twitter.finagle.group.StabilizingGroup
import com.twitter.finagle.{Group, Resolver, InetResolver, Addr}
import com.twitter.thrift.ServiceInstance
import com.twitter.thrift.Status.ALIVE
import com.twitter.util.{Future, Return, Throw, Try, Var}
import java.net.{InetSocketAddress, SocketAddress}
import scala.collection.JavaConverters._

class ZkResolverException(msg: String) extends Exception(msg)

private[finagle] class ZkGroup(serverSet: ServerSet, path: String)
    extends Thread("ZkGroup(%s)".format(path))
    with Group[ServiceInstance]
{
  setDaemon(true)
  start()

  protected val _set = Var(Set[ServiceInstance]())

  override def run() {
    serverSet.monitor(new DynamicHostSet.HostChangeMonitor[ServiceInstance] {
      def onChange(newSet: ImmutableSet[ServiceInstance]) = synchronized {
        _set() = newSet.asScala.toSet
      }
    })
  }
}

class ZkResolver(factory: ZkClientFactory) extends Resolver {
  val scheme = "zk"

  def this() = this(DefaultZkClientFactory)

  def resolve(zkHosts: Set[InetSocketAddress], 
      path: String, endpoint: Option[String]): Var[Addr] = {
    val (zkClient, zkHealthHandler) = factory.get(zkHosts)
    val zkGroup = endpoint match {
      case Some(endpoint) =>
        (new ZkGroup(new ServerSetImpl(zkClient, path), path)) collect {
          case inst if inst.getStatus == ALIVE && inst.getAdditionalEndpoints.containsKey(endpoint) =>
            val ep = inst.getAdditionalEndpoints.get(endpoint)
            new InetSocketAddress(ep.getHost, ep.getPort): SocketAddress
        }

      case None =>
        (new ZkGroup(new ServerSetImpl(zkClient, path), path)) collect {
          case inst if inst.getStatus == ALIVE =>
            val ep = inst.getServiceEndpoint
            new InetSocketAddress(ep.getHost, ep.getPort): SocketAddress
        }
    }
    
    // TODO: get rid of groups underneath.
    val g = StabilizingGroup(
      zkGroup,
      zkHealthHandler,
      factory.sessionTimeout,
      DefaultStatsReceiver.scope("zkGroup"))

    g.set map { newSet => Addr.Bound(newSet) }
  }

  private[this] def zkHosts(hosts: String) = {
    val zkHosts = factory.hostSet(hosts)
    if (zkHosts.isEmpty)
      throw new ZkResolverException("ZK client address \"%s\" resolves to nothing".format(hosts))
    zkHosts
  }

  def bind(arg: String) = arg.split("!") match {
    // zk!host:2181!/path
    case Array(hosts, path) =>
      resolve(zkHosts(hosts), path, None)

    // zk!host:2181!/path!endpoint
    case Array(hosts, path, endpoint) =>
      resolve(zkHosts(hosts), path, Some(endpoint))

    case _ =>
      throw new ZkResolverException("Invalid address \"%s\"".format(arg))
  }
}
