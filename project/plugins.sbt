resolvers += "SimpleModeling.org" at "https://www.simplemodeling.org/repository/maven"
resolvers += Resolver.defaultLocal

val sbtCozyVersion = sys.props.getOrElse("sbt.cozy.version", sys.env.getOrElse("SBT_COZY_VERSION", "0.1.16"))
addSbtPlugin("org.goldenport" % "sbt-cozy" % sbtCozyVersion)
