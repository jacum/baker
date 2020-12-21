package com.ing.baker.runtime.akka.actor.recipes

import cats.effect.concurrent.Ref
import cats.effect.{IO, Sync}
import cats.implicits._
import com.ing.baker.il.CompiledRecipe

import scala.collection.mutable

trait Recipes[F[_]] {

  def get(recipeId: String): F[Either[String, (CompiledRecipe, Long)]]
  def list: F[List[(CompiledRecipe, Long)]]

}

trait MutableRecipes[F[_]] extends Recipes[F] {

  implicit val sync: Sync[F]

  private val recipesById: F[Ref[F, mutable.Map[String, (CompiledRecipe, Long)]]] =
    Ref.of[F, mutable.Map[String, (CompiledRecipe, Long)]](mutable.Map[String, (CompiledRecipe, Long)]())

  def update(compiledRecipe: CompiledRecipe, timestamp: Long = System.currentTimeMillis()): F[Unit] = for {
    recipeRef <- recipesById
    r <- recipeRef.get
  } yield r  += (compiledRecipe.recipeId -> (compiledRecipe, timestamp))

  def get(recipeId: String): F[Either[String, (CompiledRecipe, Long)]] = for {
    recipeRef <- recipesById
    r <- recipeRef.get
  } yield  r.get(recipeId) match {
    case Some((compiledRecipe, timestamp)) => Right((compiledRecipe, timestamp))
    case None => Left(recipeId)
  }

  def list: F[List[(CompiledRecipe, Long)]] = for {
    recipeRef <- recipesById
    r <- recipeRef.get
  } yield  r.values.toList

}

