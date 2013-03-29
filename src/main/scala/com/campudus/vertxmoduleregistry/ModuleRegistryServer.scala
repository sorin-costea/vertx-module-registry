package com.campudus.vertxmoduleregistry

import org.vertx.java.platform.Verticle
import org.vertx.java.core.http.RouteMatcher
import org.vertx.java.core.http.HttpServerRequest
import org.vertx.java.core.Handler
import com.campudus.vertx.helpers.VertxScalaHelpers
import org.vertx.java.core.buffer.Buffer
import com.campudus.vertx.helpers.PostRequestReader
import com.campudus.vertxmoduleregistry.database.Database._
import com.campudus.vertxmoduleregistry.security.Authentication._
import org.vertx.java.core.json.JsonObject
import org.vertx.java.core.json.JsonArray
import scala.util.Success
import scala.util.Failure
import scala.concurrent.ExecutionContext.Implicits.global
import java.net.URLDecoder
import org.vertx.java.core.AsyncResult
import org.vertx.java.core.file.FileProps

class ModuleRegistryServer extends Verticle with VertxScalaHelpers {

  private def getRequiredParam(param: String, error: String)(implicit paramMap: Map[String, String], errors: collection.mutable.ListBuffer[String]) = {
    def addError() = {
      errors += error
      ""
    }

    paramMap.get(param) match {
      case None => addError
      case Some(str) if (str.matches("\\s*")) => addError
      case Some(str) => URLDecoder.decode(str, "utf-8")
    }
  }

  override def start() {
    val rm = new RouteMatcher

    rm.get("/", { req: HttpServerRequest =>
      req.response.sendFile(getWebPath() + "/index.html")
    })
    rm.get("/register", { req: HttpServerRequest =>
      req.response.sendFile(getWebPath() + "/register.html")
    })

    rm.get("/latest-approved-modules", {
      req: HttpServerRequest =>
        val limit = req.params().get("limit") match {
          case s: String => toInt(s).getOrElse(5)
          case _ => 5
        }

        latestApprovedModules(vertx, limit).onComplete {
          case Success(modules) =>
            /*
             {modules: [{...},{...}]}
             */
            val modulesArray = new JsonArray()
            modules.map(_.toJson).foreach(m => modulesArray.addObject(m))
            req.response.end(json.putArray("modules", modulesArray).encode)
          case Failure(error) =>
            req.response.end(json.putString("status", "failed").putString("message", error.getMessage()).encode)
        }

    })

    rm.get("/unapproved", {
      req: HttpServerRequest =>
        unapproved(vertx).onComplete {
          case Success(modules) =>
            val modulesArray = new JsonArray()
            modules.map(_.toJson).foreach(m => modulesArray.addObject(m))
            req.response.end(json.putArray("modules", modulesArray).encode)
          case Failure(error) =>
            req.response.end(json.putString("status", "failed").putString("message", error.getMessage()).encode)
        }
    })

    rm.post("/approve", {
      req: HttpServerRequest =>
        req.dataHandler({ buf: Buffer =>
          implicit val paramMap = PostRequestReader.dataToMap(buf.toString)
          implicit val errorBuffer = collection.mutable.ListBuffer[String]()

          val sessionId = getRequiredParam("sessionId", "Session ID required")
          val id = getRequiredParam("_id", "Module ID required")

          val errors = errorBuffer.result
          if (errors.isEmpty) {
            authorise(vertx, sessionId).onComplete {
              case Success(username) =>
                approve(vertx, id).onComplete {
                  case Success(json) =>
                    req.response.end(json.encode())
                  case Failure(error) =>
                    req.response.end(json.putString("status", "failed").putString("message", error.getMessage()).encode)
                }
              case Failure(error) =>
                req.response.end(json.putString("status", "denied").encode())
            }

          } else {
            val errorsAsJsonArr = new JsonArray
            errors.foreach(err => errorsAsJsonArr.addString(err))
            req.response.end(json.putString("status", "error").putArray("messages", errorsAsJsonArr).encode)
          }
        })
    })

    rm.post("/search", { req: HttpServerRequest =>
      req.dataHandler({ buf: Buffer =>
        implicit val paramMap = PostRequestReader.dataToMap(buf.toString)
        implicit val errorBuffer = collection.mutable.ListBuffer[String]()

        val query = getRequiredParam("query", "Cannot search with empty keywords")

        val errors = errorBuffer.result
        if (errors.isEmpty) {
          searchModules(vertx, query).onComplete {
            case Success(modules) =>
              val modulesArray = new JsonArray()
              modules.map(_.toJson).foreach(m => modulesArray.addObject(m))
              req.response.end(json.putArray("modules", modulesArray).encode)
            case Failure(error) =>
              req.response.end(json.putString("status", "failed").putString("message", error.getMessage()).encode)
          }
        } else {
          req.response.end
        }
      })
    })

    rm.post("/register", { req: HttpServerRequest =>
      req.dataHandler({ buf: Buffer =>
        implicit val paramMap = PostRequestReader.dataToMap(buf.toString)

        implicit val errorBuffer = collection.mutable.ListBuffer[String]()

        val downloadUrl = getRequiredParam("downloadUrl", "Download URL missing")
        val name = getRequiredParam("modname", "Missing name")
        val owner = getRequiredParam("modowner", "Missing owner")
        val version = getRequiredParam("version", "Missing version of module")
        val vertxVersion = getRequiredParam("vertxVersion", "Missing vertx version")
        val description = getRequiredParam("description", "Missing description")
        val projectUrl = getRequiredParam("projectUrl", "Missing project URL")
        val author = getRequiredParam("author", "Missing author")
        val email = getRequiredParam("email", "Missing contact email")
        val license = getRequiredParam("license", "Missing license")
        val keywords = paramMap.get("keywords") match {
          case None => List()
          case Some(words) => URLDecoder.decode(words, "utf-8").split("\\s*,\\s*").toList
        }

        val errors = errorBuffer.result
        if (errors.isEmpty) {
          val module = Module(downloadUrl, name, owner, version, vertxVersion, description, projectUrl, author, email, license, keywords, System.currentTimeMillis())
          registerModule(vertx, module).onComplete {
            case Success(json) =>
              req.response.end("Registered: " + module + " with id " + json.getString("_id"))
            case Failure(error) =>
              req.response.end(json.putString("status", "failed").putString("message", error.getMessage()).encode)
          }
        } else {
          val errorsAsJsonArr = new JsonArray
          errors.foreach(err => errorsAsJsonArr.addString(err))
          req.response.end(json.putString("status", "error").putArray("messages", errorsAsJsonArr).encode)
        }

      })
    })

    rm.post("/login", { req: HttpServerRequest =>
      req.dataHandler({ buf: Buffer =>
        implicit val paramMap = PostRequestReader.dataToMap(buf.toString)
        implicit val errorBuffer = collection.mutable.ListBuffer[String]()

        val username = getRequiredParam("username", "Missing username")
        val password = getRequiredParam("password", "Missing password")

        val errors = errorBuffer.result
        if (errors.isEmpty) {
          login(vertx, username, password).onComplete {
            case Success(sessionId) =>
              req.response.end(json.putString("status", "ok").putString("sessionId", sessionId).encode)
            case Failure(error) =>
              req.response.end(json.putString("status", "denied").encode())
          }
        } else {
          val errorsAsJsonArr = new JsonArray
          errors.foreach(err => errorsAsJsonArr.addString(err))
          req.response.end(json.putString("status", "error").putArray("messages", errorsAsJsonArr).encode)
        }
      })
    })

    rm.post("/logout", { req: HttpServerRequest =>
      req.dataHandler({ buf: Buffer =>
        implicit val paramMap = PostRequestReader.dataToMap(buf.toString)
        implicit val errorBuffer = collection.mutable.ListBuffer[String]()

        val sessionId = getRequiredParam("sessionId", "Session ID required")

        val errors = errorBuffer.result
        if (errors.isEmpty) {
          logout(vertx, sessionId).onComplete {
            case Success(oldSessionId) =>
              req.response.end(json.putString("status", "ok").putString("sessionID", oldSessionId).encode)
            case Failure(error) =>
              req.response.end(json.putString("status", "failed").encode())
          }
        } else {
          val errorsAsJsonArr = new JsonArray
          errors.foreach(err => errorsAsJsonArr.addString(err))
          req.response.end(json.putString("status", "error").putArray("messages", errorsAsJsonArr).encode)
        }
      })
    })

    rm.getWithRegEx("^(.*)$", { implicit req: HttpServerRequest =>
      val param0 = trimSlashes(req.params.get("param0"))
      val param = if (param0 == "") {
        "index.html"
      } else {
        param0
      }
      val errorDir = getWebPath() + "/errors"

      if (param.contains("..")) {
        deliver(403, errorDir + "403.html")
      } else {
        val path = getWebPath() + param

        vertx.fileSystem().props(path, {
          fprops: AsyncResult[FileProps] =>
            if (fprops.succeeded()) {
              if (fprops.result.isDirectory) {
                val indexFile = path + "/index.html"
                vertx.fileSystem().exists(indexFile, {
                  exists: AsyncResult[java.lang.Boolean] =>
                    if (exists.succeeded() && exists.result) {
                      deliver(indexFile)
                    } else {
                      deliver(404, errorDir + "/404.html")
                    }
                })
              } else {
                deliver(path)
              }
            } else {
              deliver(404, errorDir + "/404.html")
            }
        })
      }
    })

    vertx.createHttpServer().requestHandler(rm).listen(8080);
    println("started server")
  }

  override def stop() {
    println("stopped server")
  }

  private def getWebPath() = System.getProperty("user.dir") + "/web"

  private def trimSlashes(path: String) = {
    path.replace("^/+", "").replace("/+$", "")
  }

  private def deliver(file: String)(implicit request: HttpServerRequest): Unit = deliver(200, file)(request)
  private def deliver(statusCode: Int, file: String)(implicit request: HttpServerRequest) {
    println("Delivering: " + file + " with code " + statusCode)
    request.response.statusCode = statusCode
    request.response.sendFile(file)
  }

  private def deliverValidUrl(file: String)(implicit request: HttpServerRequest) {
    if (file.contains("..")) {
      deliver(403, "errors/403.html")
    } else {
      deliver(file)
    }
  }
}