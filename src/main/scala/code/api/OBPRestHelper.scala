package code.api

import net.liftweb.http.rest.RestHelper
import net.liftweb.http.Req
import net.liftweb.common._
import net.liftweb.http.LiftResponse
import net.liftweb.http.JsonResponse
import code.util.APIUtil._
import code.model.User
import code.api.OAuthHandshake._
import net.liftweb.json.JsonAST.JValue
import net.liftweb.http.RequestType
import net.liftweb.http.ParsePath
import net.liftweb.http.LiftRules
import net.liftweb.http.ParamCalcInfo
import net.liftweb.http.provider.HTTPRequest
import net.liftweb.http.provider.servlet.HTTPRequestServlet
import net.liftweb.http.LiftServlet
import javax.servlet.http.HttpServletRequest
import org.apache.commons.lang.NotImplementedException
import net.liftweb.json.Extraction

class ConvertableToJson[T](conv : T) {
  def toJValue = Extraction.decompose(conv)
}

class OBPRestHelper extends RestHelper with Loggable {

  implicit def jsonResponseBoxToJsonReponse(box: Box[JsonResponse]): JsonResponse = {
    box match {
      case Full(r) => r
      case Failure(msg, _, _) => {
        logger.info("API Failure: " + msg)
        errorJsonResponse(msg)
      }
      case _ => errorJsonResponse()
    }
  }

  def failIfBadOauth(fn: (Box[User]) => Box[JsonResponse]) : JsonResponse = {
    if (isThereAnOAuthHeader) {
      getUser match {
        case Full(u) => fn(Full(u))
        case Failure(msg, _, _) => errorJsonResponse(msg)
        case _ => errorJsonResponse("oauth error")
      }
    } else fn(Empty)
  }

  class RichStringList(list: List[String]) {
    val listLen = list.length

    /**
     * Normally we would use ListServeMagic's prefix function, but it works with PartialFunction[Req, () => Box[LiftResponse]]
     * instead of the PartialFunction[Req, Box[User] => Box[JsonResponse]] that we need. This function does the same thing, really.
     */
    def oPrefix(pf: PartialFunction[Req, Box[User] => Box[JsonResponse]]): PartialFunction[Req, Box[User] => Box[JsonResponse]] =
      new PartialFunction[Req, Box[User] => Box[JsonResponse]] {
        def isDefinedAt(req: Req): Boolean =
          req.path.partPath.startsWith(list) && {
            pf.isDefinedAt(req.withNewPath(req.path.drop(listLen)))
          }

        def apply(req: Req): Box[User] => Box[JsonResponse] =
          pf.apply(req.withNewPath(req.path.drop(listLen)))
      }
    
    def oPrefix2[IN, OUT](pf: PartialFunction[Req, (Box[User], Box[IN]) => Box[OUT]]): PartialFunction[Req, (Box[User], Box[IN]) => Box[OUT]] =
      new PartialFunction[Req, (Box[User], Box[IN]) => Box[OUT]] {
        def isDefinedAt(req: Req): Boolean =
          req.path.partPath.startsWith(list) && {
            pf.isDefinedAt(req.withNewPath(req.path.drop(listLen)))
          }

        def apply(req: Req): (Box[User], Box[IN]) => Box[OUT] =
          pf.apply(req.withNewPath(req.path.drop(listLen)))
      }
  }
  
  trait PathElement {
    def name : String
  }
  case class StaticElement(name : String) extends PathElement
  case class VariableElement(name : String) extends PathElement
  type ApiPath = List[PathElement]
  
  def caseClassBoxToJsonResponse[T](output : Box[T]) : Box[JsonResponse] = {
    output.map(x => successJsonResponse(Extraction.decompose(x)))
  }
  
  //TODO: input and output should be optional
  def registerApiCall[INPUT, OUTPUT](path : ApiPath, reqType : RequestType, handler : PartialFunction[Req, (Box[User], Box[INPUT]) => Box[OUTPUT]])
  (implicit m: ClassManifest[OUTPUT], m2 : Manifest[INPUT]) = {
    
    val testPath : List[String] = path.map{
      case StaticElement(name) => name
      case VariableElement(_) => "test"
    }
    
    val reqPath = ParsePath(testPath, "", true, false)
    import net.liftweb.http.provider.HTTPProvider
    
    //Some bits of httpRequest have to be mocked to avoid exceptions in handler.isDefinedAt(testRequest)
    import org.mockito.Mockito._
    val httpRequest : HTTPRequest = mock(classOf[HTTPRequest])
    when(httpRequest.contentType).thenReturn(Full("application/json"))
    when(httpRequest.headers).thenReturn(Nil)
    
    val testRequest : Req = new Req(reqPath, LiftRules.context.path, reqType,
        Full("application/json"), httpRequest, 5, 6, () => ParamCalcInfo(Nil, Map(), Nil, Empty), Map())
    

    import net.liftweb.util.Helpers.tryo
    val aaa = m.erasure.getCanonicalName()
    println("AAA: " + aaa)
    val bbb = m2.erasure.getCanonicalName()
    println("BBB: " + bbb)
    //TODO assuming it's a case class, use 2.10 reflection to generate some sample json
    
    //Convert the handler into the more general Box[User] => Box[JsonResponse] that oauthServe expects
    val oauthHandler = new PartialFunction[Req, Box[User] => Box[JsonResponse]] {
      def isDefinedAt(req: Req) : Boolean = handler.isDefinedAt(req)
      
      def apply(req: Req) : Box[User] => Box[JsonResponse] = {
        val foo = handler.apply(req)
        val json = req.json
        import net.liftweb.json._
        val in = tryo{json.map(_.extract[INPUT](DefaultFormats, m2))}.getOrElse(Empty)
        (user: Box[User]) => caseClassBoxToJsonResponse(handler.apply(req).apply(user, in))
      }
    }
    
    if(handler.isDefinedAt(testRequest)) {
      
      //TODO how can we verify the expected input? test it and see if the result is a "bad format" error? -> that would require someone explicitely write this
      //particular error type in every (ne
      
      oauthServe(oauthHandler)
      //TODO add to docs
      logger.info("added api call!!!")
    }
    else {
      logger.error("Api call did not fulfill documented behaviour!!!") //TODO: describe which api call
    }
    
  }

  //Give all lists of strings in OBPRestHelpers the oPrefix method
  implicit def stringListToRichStringList(list : List[String]) : RichStringList = new RichStringList(list)

  //TODO: Fold this into registerApiCall once that method is properly implemented
  def oauthServe(handler : PartialFunction[Req, Box[User] => Box[JsonResponse]]) : Unit = {
    val obpHandler : PartialFunction[Req, () => Box[LiftResponse]] = {
      new PartialFunction[Req, () => Box[LiftResponse]] {
        def apply(r : Req) = {
          failIfBadOauth {
            handler(r)
          }
        }
        def isDefinedAt(r : Req) = handler.isDefinedAt(r)
      }
    }
    serve(obpHandler)
  }

  override protected def serve(handler: PartialFunction[Req, () => Box[LiftResponse]]) : Unit= {

    val obpHandler : PartialFunction[Req, () => Box[LiftResponse]] = {
      new PartialFunction[Req, () => Box[LiftResponse]] {
        def apply(r : Req) = {
          //Wraps the partial function with some logging
          logAPICall
          handler(r)
        }
        def isDefinedAt(r : Req) = handler.isDefinedAt(r)
      }
    }
    super.serve(obpHandler)
  }


}