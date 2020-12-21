package com.ing.baker.runtime.akka.actor.recipe_manager

import java.util.UUID

import akka.actor.ActorRef
import akka.pattern.ask
import com.ing.baker.BakerRuntimeTestBase
import com.ing.baker.compiler.RecipeCompiler
import com.ing.baker.recipe.TestRecipe
import com.typesafe.config.{Config, ConfigFactory}

object RecipesSpec {
  val config: Config = ConfigFactory.parseString(
    """
      |akka.persistence.journal.plugin = "inmemory-journal"
      |akka.persistence.snapshot-store.plugin = "inmemory-snapshot-store"
      |akka.test.timefactor = 3.0
    """.stripMargin)
}

class RecipesSpec extends BakerRuntimeTestBase {

  override def actorSystemName = "RecipeManagerSpec"

  "The recipes" should {
    "add a recipe to the list when a new .recipe file appears is received" in {
      val compiledRecipe = RecipeCompiler.compileRecipe(TestRecipe.getRecipe("AddRecipeRecipe"))

      val recipeManager: ActorRef = defaultActorSystem.actorOf(RecipeManager.props(), s"recipeManager-${UUID.randomUUID().toString}")

      for {
        futureAddResult <- recipeManager.ask(AddRecipe(compiledRecipe))(timeout)
        recipeId: String = futureAddResult match {
          case AddRecipeResponse(x) => x
          case _ => fail("Adding recipe failed")
        }
        futureGetResult <- recipeManager.ask(GetRecipe(recipeId))(timeout)
        _ = futureGetResult match {
          case RecipeFound(_, _) => succeed
          case NoRecipeFound(_) => fail("Recipe not found")
          case _ => fail("Unknown response received")
        }
      } yield succeed
    }
  }
}
