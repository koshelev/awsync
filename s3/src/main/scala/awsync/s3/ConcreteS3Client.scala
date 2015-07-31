package awsync.s3

import java.util.Date
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.ByteRange
import akka.stream.Materializer
import akka.stream.scaladsl.Source

import scala.collection.immutable.Seq
import scala.xml.{Elem, XML}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import akka.actor.ActorSystem
import akka.util.ByteString
import awsync.{Regions, Region, Credentials}

private[s3] object ConcreteS3Client {

  def apply(credentials: Credentials, region: Region, https: Boolean = true)(implicit system: ActorSystem, materializer: Materializer): S3Client =
    new ConcreteS3Client(credentials, region, https)(system, materializer)

  private def conditionToHeader(condition: GetObjectCondition): HttpHeader = condition match {
    case IfMatch(ETag(tag)) => headers.`If-Match`(headers.EntityTag(tag))
    case IfNotMatch(ETag(tag)) => headers.`If-None-Match`(headers.EntityTag(tag))
    case IfModifiedSince(date) => headers.`If-Modified-Since`(DateTime(date.getTime))
    case IfNotModifiedSince(date) => headers.`If-Unmodified-Since`(DateTime(date.getTime))
  }

  private def path(key: Key): Uri.Path = Uri.Path("/" + key.name)

  private def handleSimpleResponse[A](operation: String)(onOk: Elem => Try[A])(implicit fm: Materializer, ec: ExecutionContext): HttpResponse => Future[A] = {
  (response: HttpResponse) =>
    response.entity.dataBytes
      .runFold[ByteString](ByteString())((acc, chunk) => acc ++ chunk)
      .map { bytes =>
      // TODO is there a less wasteful way to transform bytestring => string ?
      val bodyAsText: String = new String(bytes.toArray, "UTF-8")
      if (response.status == StatusCodes.OK) onOk(XML.loadString(bodyAsText)).get
      else throw exceptionFor(response, operation, Some(bodyAsText))
    }
  }

  private def exceptionFor(response: HttpResponse, operation: String, body: Option[String] = None): Nothing =
    throw new RuntimeException(s"Failed request $operation, status: ${response.status}, body: $body")




}

private[s3] final class ConcreteS3Client(
    credentials: Credentials, region: Region, val https: Boolean
  )(
    implicit val system: ActorSystem, val materializer: Materializer
  ) extends S3Connections with S3Client {

  import HttpMethods._
  import RequestUtils._
  import ConcreteS3Client._

  // api implementation

  override def listBuckets: Future[Seq[(BucketName, Date)]] =
    sendRequest(signedRequest(GET, baseUri, Regions.USEast, credentials))
      .flatMap(handleSimpleResponse("listing buckets")(xml.ListBuckets.fromXml))

  override def listObjectStream(bucket: BucketName, config: ListObjectsConfig): Future[(ListObjectsInfo, Source[Seq[KeyDetails], Unit])] = {
    val parameters = Seq(
      config.delimiter.map("delimiter" -> _),
      config.marker.map("marker" -> _),
      config.maxKeys.map("maxKeys" -> _.toString),
      config.prefix.map("prefix" -> _)
    )
    val uri = bucketBaseUri(bucket).withQuery(parameters.flatten: _*)
    sendBucketRequest(bucket, signedRequest(GET, uri, region, credentials))
      .flatMap(handleSimpleResponse("listing objects")(xml.ListObjects.fromXml))
      .map { case (info, keys) =>
        if (info.isTruncated) {
          throw new RuntimeException("Truncated object lists not supported yet")

        } else {
          (info, Source.single(keys))
        }

      }
  }

  override def canAccess(bucket: BucketName): Future[Option[NoAccessReason]] =
    sendBucketRequest(bucket, signedRequest(HEAD, bucketBaseUri(bucket), region, credentials))
      .map { response =>
        response.status match {
          case StatusCodes.OK => None
          case StatusCodes.NotFound => Some(DoesNotExist)
          case StatusCodes.Forbidden => Some(PermissionDenied)
          case _ => exceptionFor(response, s"checking if we have access to bucket $bucket", None)
        }
      }

  override def getObjectMetadata(key: FqKey): Future[Option[S3ObjectMetadata]] = {
    val request = signedRequest(HEAD, bucketBaseUri(key.bucket).withPath(path(key.key)), region, credentials)
    sendBucketRequest(key.bucket, request)
      .map { response =>
        response.status match {
          case StatusCodes.OK => Some(S3ObjectMetadata(response.headers.map(h => h.name -> h.value)))
          case StatusCodes.NotFound => None
          case _ => exceptionFor(response, s"fetching metadata for $key", None)
        }
    }
  }

  override def getObjectStream(key: FqKey, range: Option[ByteRange], conditions: Option[GetObjectCondition]): Future[(S3ObjectMetadata, Source[ByteString, Unit])] = {
    val uri = bucketBaseUri(key.bucket).withPath(path(key.key))
    val request = HttpRequest(GET, uri,
      List(
        Some[HttpHeader](headers.Host(uri.authority.host.address)),
        range.map(headers.Range(_)),
        conditions.map(conditionToHeader)
      ).flatten: List[HttpHeader]
    )
    sendBucketRequest(key.bucket, signedRequest(request, region, credentials)).map[(S3ObjectMetadata, Source[ByteString, Unit])] { response =>
      response.status match {
        case StatusCodes.OK =>
          (
            S3ObjectMetadata(response.headers.map(h => h.name -> h.value)),
            response.entity.dataBytes.mapMaterializedValue(_ => Unit)
          )

        case StatusCodes.NotFound => throw DoesNotExist
        case StatusCodes.NotModified => throw NotModified
        case StatusCodes.PreconditionFailed if conditions.exists(i => i.isInstanceOf[IfMatch] || i.isInstanceOf[IfNotModifiedSince]) => throw NotModified
        case _ => exceptionFor(response, "fetching object", None)
      }
    }
  }

  override def createObjectFromStream(key: FqKey, contentType: ContentType, data: Source[ByteString, Unit], config: CreateObjectConfig): Future[Unit] = {
    ???
  }

  override def createObject(key: FqKey, contentType: ContentType, data: ByteString, config: CreateObjectConfig): Future[Unit] = {
    val uri = bucketBaseUri(key.bucket).withPath(path(key.key))

    val headerList: List[HttpHeader] =
      List(
        Some[HttpHeader](headers.Host(uri.authority.host.address)),
        config.cacheControl.map(headers.`Cache-Control`(_)),
        /* config.cannedAcl.fold(
           canned -> None //Some(RawHeader("x-amz-acl" -> canned.name)),
           permissions -> None
         */
        config.contentDisposition.map(headers.`Content-Disposition`(_)),
        Some(headers.RawHeader("x-amz-storage-class", config.storageClass.name))
      ).flatten ++ config.customMetadata.map(t => headers.RawHeader(t._1.name, t._2))

    val request = HttpRequest(PUT, uri,
      headerList,
      HttpEntity(contentType, data)
    )

    sendBucketRequest(key.bucket, signedRequest(request, region, credentials)).map { response => () }
  }


  override def deleteObject(key: FqKey): Future[Unit] = {
    val request = signedRequest(DELETE, bucketBaseUri(key.bucket).withPath(path(key.key)), region, credentials)
    sendBucketRequest(key.bucket, request)
      .map { response =>
      response.status match {
        case StatusCodes.NoContent => Unit
        case StatusCodes.NotFound => throw new RuntimeException(s"Cannot delete key $key because it does not exist")
        case _ => exceptionFor(response, "deleting object")
      }
    }

  }


  override def deleteObjects(bucket: BucketName, keys: Seq[Key]): Future[Unit] = {
    val body = xml.DeleteKeys.toXml(keys).toString

    val request = signedRequest(POST, bucketBaseUri(bucket).withQuery("delete"), body, region, credentials)

    sendBucketRequest(bucket, request)
      .map { response =>
        response.status match {
          case StatusCodes.OK =>
            // TODO parse response and throw error with the failed deletes if there were any
            Unit
          case _ => exceptionFor(response, "deleting multiple object")
        }
      }
  }

}