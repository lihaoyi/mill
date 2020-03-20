package mill.contrib.bintray

import mill.api.Logger

import java.time.{ZoneOffset, ZonedDateTime}
import java.time.format.DateTimeFormatter
import java.util.Base64

import scala.concurrent.duration._

class BintrayHttpApi(
  owner: String,
  repo: String,
  credentials: String,
  readTimeout: Int,
  connectTimeout: Int,
  log: Logger
) {
  val http = requests.Session(readTimeout = readTimeout, connectTimeout = connectTimeout, maxRedirects = 0, check = false)

  private val uploadTimeout = 5.minutes.toMillis.toInt

  def now = ZonedDateTime.now(ZoneOffset.UTC)

  // https://www.jfrog.com/confluence/display/BT/Bintray+REST+API#BintrayRESTAPI-UploadContent
  def upload(pkg: String, version: String, path: String, contentType: String, data: Array[Byte]): requests.Response = {
    log.info(s"Uploading $path")
    http.put(
      s"${Paths.upload(pkg, version)}/$path",
      readTimeout = uploadTimeout,
      headers = Seq(
        "Content-Type" -> contentType,
        "Authorization" -> Auth.basic
      ),
      data = data
    )
  }

  def createVersion(
    pkg: String,
    version: String,
    releaseDate: ZonedDateTime = now,
    description: String = ""
  ): requests.Response = {
    log.info(s"Creating version $version")
    http.post(
      Paths.version(pkg),
      headers = Seq(
        "Content-Type" -> ContentTypes.json,
        "Authorization" -> Auth.basic
      ),
      data = s"""{
                |  "desc": "$description",
                |  "released": "${releaseDate.format(DateTimeFormatter.ISO_INSTANT)}",
                |  "name": "$version"
                |}""".stripMargin
    )
  }

  object Paths {
    val root = "https://api.bintray.com"
    def upload(pkg: String, version: String) = s"$root/content/$owner/$repo"
    def version(pkg: String) = s"$root/packages/$owner/$repo/$pkg/versions"
  }

  object ContentTypes {
    val jar  = "application/java-archive"
    val xml  = "application/xml"
    val json = "application/json"
  }

  object Auth {
    val basic = s"Basic ${base64(credentials)}"
  }

  private def base64(s: String) =
    new String(Base64.getEncoder.encode(s.getBytes))
}
