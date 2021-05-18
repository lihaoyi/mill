package mill.bsp.newbsp

import mainargs.{Flag, arg}

case class BspServerConfig(
    ammoniteCore: ammonite.main.Config.Core,
    @arg(name = "jobs", short = 'j')
    threadCount: Option[Int] = Option(1),
    @arg(name = "keep-going", short = 'k')
    keepGoing: Flag = Flag(),
    dir: Option[String] = None,
    help: Flag = Flag(),
    @arg(doc = "Write a BSP connection file (`.bsp/mill.json`)")
    install: Flag = Flag()
)
