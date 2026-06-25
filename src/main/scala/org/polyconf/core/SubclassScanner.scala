package org.polyconf.core

import java.io.File
import java.lang.reflect.Modifier
import java.net.URLClassLoader
import java.util.jar.JarFile
import org.apache.logging.log4j.LogManager
import org.polyconf.util.{PolyLog, PolyUtil}

import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}

/** Scans the classpath for concrete subclasses of a given base class. */
object SubclassScanner {

  private val log = LogManager.getLogger(getClass)
  private val knownPackages = Seq("org.polyconf")

  def findConcreteSubclasses(base: Class[_ <: PolyConf]): Seq[Class[_ <: PolyConf]] = {
    val urls = classloaderUrls(base)
    val classNames = urls.flatMap { url =>
      if (url.getProtocol != "file") Seq.empty
      else {
        val f = new File(url.toURI)
        if (f.isDirectory) scanDirectory(f, f)
        else if (f.getName.endsWith(".jar")) scanJar(f)
        else Seq.empty
      }
    }
    classNames.distinct.flatMap(loadClass(_, base))
  }

  private def classloaderUrls(base: Class[_]): Seq[java.net.URL] = {
    val cl = base.getClassLoader
    cl match {
      case urlCl: URLClassLoader => urlCl.getURLs.toSeq
      case _ =>
        // Fallback: try java.class.path
        val separator = if (File.pathSeparator == ";") ";" else ":"
        Option(System.getProperty("java.class.path")).toSeq
          .flatMap(_.split(separator).toSeq)
          .map(p => new File(p).toURI.toURL)
    }
  }

  private def scanDirectory(root: File, dir: File): Seq[String] = {
    val files = dir.listFiles()
    if (files == null) {
      PolyLog.Log.warn(s"Could not list files in directory: $dir")
      Seq.empty
    } else {
      files.toSeq.flatMap { f =>
        if (f.isDirectory) scanDirectory(root, f)
        else if (f.getName.endsWith(".class")) classNameFromFile(root, f)
        else None
      }
    }
  }

  private def classNameFromFile(root: File, classFile: File): Option[String] = {
    val path = classFile.getAbsolutePath
    val base = root.getAbsolutePath
    if (path.startsWith(base)) {
      val relative = path.substring(base.length + 1)
      val cn = relative
        .replace(File.separator, ".")
        .replace('/', '.')
        .replace('\\', '.')
        .stripSuffix(".class")
      if (cn.contains("$")) None
      else if (knownPackages.exists(cn.startsWith)) Some(cn)
      else None
    } else None
  }

  private def scanJar(jarFile: File): Seq[String] = {
    PolyUtil.withResource(new JarFile(jarFile)) { jar =>
      jar.entries().asScala
        .filter(e => e.getName.endsWith(".class") && !e.isDirectory)
        .map(_.getName.replace('/', '.').stripSuffix(".class"))
        .filterNot(_.contains("$"))
        .filter(cn => knownPackages.exists(cn.startsWith))
        .toSeq
    }.getOrElse(Seq.empty)
  }

  private def loadClass(className: String, base: Class[_ <: PolyConf]): Option[Class[_ <: PolyConf]] = {
    Try(Class.forName(className, false, getClass.getClassLoader)) match {
      case Failure(e) =>
        log.error(s"Failed to load class '$className': ${e.getMessage}", e)
        None
      case Success(clazz) =>
        if (isConcreteSubclass(clazz, base))
          Some(clazz.asInstanceOf[Class[_ <: PolyConf]])
        else None
    }
  }

  private def isConcreteSubclass(clazz: Class[_], base: Class[_]): Boolean = {
    base.isAssignableFrom(clazz) &&
      clazz != base &&
      !clazz.isInterface &&
      !clazz.isAnnotation &&
      !clazz.isEnum &&
      !clazz.isSynthetic &&
      !clazz.isAnonymousClass &&
      !Modifier.isAbstract(clazz.getModifiers)
  }
}
