---
layout: page
title:  "Quickstart"
categories: docs
---

This guide will get you familiar with Colossus and help you write your first service.

This quickstart assumes you are familiar with developing Scala applications
using SBT.  Furthermore you should be generally familiar with Akka and
concurrent programming concepts.

## SBT
Add the following to your Build.scala or build.sbt:

{% highlight scala %}

libraryDependencies += "com.tumblr" %% "colossus" % "{{ site.latest_version }}"

{% endhighlight %}

Colossus is compiled for Scala 2.10 and 2.11 and built against Akka 2.3.

## Anatomy of a Service

Before we get to writing code, it will help to understand the basics about how
Colossus works.  A service consists of roughly two parts:

* A Server actor that listens on a TCP port and accepts incoming client connections
* A set of Worker actors that provide the environment for processing requests from client connections

While Server actors are always identical, Workers are where the majority of a
service's business logic is performed.  Workers are event-loops, a pattern found
in most non-blocking I/O applications.  Generally a service will start one
worker per physical CPU core, and work will be distributed amongst them.  All
work for a single client connection is always performed by the same worker,
making the vast majority of Colossus single-threaded.

## Build a Hello World Service

In a standard SBT project layout, create `Main.scala`.  We'll start with a
simple "hello world" http service written in a fairly verbose manner to make
things easy to follow:

{% highlight scala %}

import colossus._
import core._
import service._
import protocols.http._
import UrlParsing._
import HttpMethod._

class HelloService(context: ServerContext) extends HttpService(context) {
  def handle = {
    case request @ Get on Root / "hello" => {
      Callback.successful(request.ok("Hello World!"))
    }
  }
}

class HelloInitiailzer(worker: WorkerRef) extends Initializer(worker) {
  
  def onConnect = context => new HelloService(context)

}


object Main extends App {

  implicit val io = IOSystem()

  Server.start("hello-world", 9000){ worker => new HelloInitializer(worker) }

}

{% endhighlight %}

This will start a basic http server on port 9000:

{% highlight plaintext %}
> curl localhost:9000
Hello World! (200 OK)

> curl localhost:9000/foo
No route for /foo (404 Not Found)

{% endhighlight %}



### A Closer Look

Let's look at this code piece-by-piece.

{% highlight scala %}
class HelloService(context: ServerContext) extends HttpService(context) {
  def handle = {
    case request @ Get on Root / "hello" => {
      Callback.successful(request.ok("Hello World!"))
    }
  }
}
{% endhighlight %}

This defines the request handler for the service.  A new request handler is
attached to every connection and does all of the actual request processing.  

The `handle` partial function is where request processing actually happens.  In
it, incoming `HttpRequest` objects are mapped to `HttpResponse`.

Notice that the return value of `handle` is of type `Callback[HttpResponse]`.
Callbacks are the concurrency mechanism Colossus provides to do non-blocking
operations.  They are similar to Scala Futures in their use, but their execution
is entirely single-threaded and managed by the Worker.  Since in this example,
no actual concurrency is required, `Callback.successful` simply wraps our final
result in a Callback.


{% highlight scala %}

class HelloInitiailzer(worker: WorkerRef) extends Initializer(worker) {
  
  def onConnect = context => new HelloService(context)

}

{% endhighlight %}

The `Initializer` is a long-lived object that is created once per Worker and
manages the service's environment within the worker.  Because Workers are
single-threaded, Initializers provide a place to share resources among all
connections handled by the worker.  In particular this is often where
outgoing connections to external services can be opened.

Initializers also are responsible for providing new connections with request
handlers.  This is how our `HelloService` handler is created.  We provide
Colossus with a function of type `ServerContext => ServerConnectionHandler` and
it gets used like a factory.

Lastly, let's look at the bootstrap code to get the service running

{% highlight scala %}
implicit val io = IOSystem()
{% endhighlight %}

An `IOSystem` is a collection of Workers with a thin management layer on top.
Servers do not manage their own workers, but instead attach to an IOSystem and
let the system do all the Worker management.  Likewise, on its own, an
IOSystem does nothing and its workers sit idle.

Because an `IOSystem` is really just a set of Akka actors, in this context
the `IOSystem` will create its own Akka `ActorSystem`, but it can also use
an existing `ActorSystem`.


{% highlight scala %}
Server.start("hello-world", 9000){ worker => new HelloInitializer(worker) }
{% endhighlight %}

This starts a Server that will listen on port 9000.  The important part here is
the third argument, which is a function of type `WorkerRef => Initializer`.
This is a function that will be sent to every Worker in the IOSystem to
initialize it, so every worker will call this function once, passing itself in
as an argumnent.

## Working with Clients

In our next example we'll write a simple HTTP frontend for Redis.  One of the
driving features of Colossus is the ability to do low-latency non-blocking
interactions with external systems.  Redis, being an in-memory database, is an
ideal candidate for the kinds of systems Colossus works best with.  

While Colossus services can easily be built to communicate with any system such
as a SQL database, it currently has native support for Redis, Memcached, and
HTTP clients.  Colossus is protocol agnostic, so writing native adapters for
any protocol is easy.


{% highlight scala %}

Server.start("redis-http", 9000){ new Initializer(_) {
  
  val redisClient = ServiceClient[Redis]("localhost", 6379)

  def onConnect = context => new HttpService(context){
    def handle = {
      case req @ Get on Root / "get" / key => {
        redisClient.send(Command("GET", key)).map{
          case BulkReply(data)  => req.ok(data.utf8String)
          case NilReply         => req.notFound("(N/A)")
          case other            => req.error(s"Unepected Redis reply: $other")
        }
      }

      case req @ Get on Root / "set" / key / value => {
        redisClient.send(Command("SET", key, value)).map{
          case StatusReply(_)   => req.ok("OK")
          case other            => req.error(s"Unepected Redis reply: $other")
        }
      }
    }
  }
}}

{% endhighlight %}

Here, we create a `ServiceClient` using the redis protocol in the service's
`Initializer`.  This means that one connection to Redis is opened per Worker,
and all connections handled by that worker will use this client.  This approach 

This gives us service that conceptually looks like:

![redis]({{site.baseurl}}/img/redis.png)

## Working with Actors and Futures

So far all the request-handling code we've seen has been effectively
single-threaded.  Even though our service has multiple workers running in
parallel, everything in the context of a single request handler is
single-threaded.

Sometimes this is not what we want.  If processing a request involves either
performing some CPU intensive operation or using a blocking API, the Worker's
event-loop will get stuck waiting, causing high latency on any other connections
that happen to be bound to it.  

{% highlight scala %}

def fibonacci(i: Long): Long = i match {
  case 1 | 2 => 1
  case n => fibonacci(n - 1) + fibonacci(n - 2)
}

implicit val io = IOSystem(numWorkers = 1)

Server.basic("fibonacci", 9000){ new HttpService(_) {

  def handle = {
    case req @ Get on Root / "fib" / Long(n) => if (n > 0) {
      Callback.successful(req.ok(fibonacci(n).toString))
    } else {
      Callback.successful(req.badRequest("number must be positive"))
    }
  }

}}

{% endhighlight %}

By starting an IOSystem with just one Worker, we can be sure all connections are
being handled by the same worker.  Now if you hit the url `/fib/1000000`, it
will take the server quite a while to calculate this.  Meanwhile, all other
requests to the server will be blocked and hang.

The best way to avoid this situation is to offload the calculation to a separate thread using Sala Futures.  This is easy to do using `Callback.fromFuture`:

{% highlight scala %}

def fibonacci(i: Long): Long = i match {
  case 1 | 2 => 1
  case n => fibonacci(n - 1) + fibonacci(n - 2)
}

def futureFibonacci(i: Long): Future[Long] = Future { fibonacci(i) }

implicit val io = IOSystem(numWorkers = 1)

Server.basic("fibonacci", 9000){ new HttpService(_) {

  def handle = {
    case req @ Get on Root / "fib" / Long(n) => if (n > 0) {
      Callback.fromFuture(fibonacciFuture(n)).map { result =>
        req.ok(result.toString)
      }
    } else {
      Callback.successful(req.badRequest("number must be positive"))
    }
  }

}}

{% endhighlight %}

This will let the calculation of the result happen in another thread, and once
the Future is complete, execution will be moved back into the Worker and the
response will be built and sent back to the client.

Of course, this works even when a connection is pipelining multiple requests at
the same time on a single connection.  Colossus will continue to process
incoming requests even while it is waiting for existing ones to complete, making
it easy to parallelize work.

## Other Cool Features

So far we've shown that Colossus is a framework that prioritizes _performance_ and
_simplicity_.  In addition to those, a great amount of emphasis is placed on
_flexibility_.  A great amount of Colossus' functionality is customizable and
replaceable.

### Receiving Messages

### Metrics

### Websocket

### Tasks


## Where to go from here

The rest of docs provide more detail about how all this works and how to
leverage more advanced features, particularly the section on [building a
service server](../serviceserver).  Also be sure to check out the
[examples]({{site.github_examples_url}}) sub-project in the Colossus repo.

