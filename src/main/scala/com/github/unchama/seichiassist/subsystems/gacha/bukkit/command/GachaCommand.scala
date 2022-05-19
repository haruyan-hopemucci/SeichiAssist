package com.github.unchama.seichiassist.subsystems.gacha.bukkit.command

import cats.data.Kleisli
import cats.effect.ConcurrentEffect.ops.toAllConcurrentEffectOps
import cats.effect.{ConcurrentEffect, IO, Sync, SyncIO}
import com.github.unchama.concurrent.NonServerThreadContextShift
import com.github.unchama.contextualexecutor.ContextualExecutor
import com.github.unchama.contextualexecutor.builder.ParserResponse.{failWith, succeedWith}
import com.github.unchama.contextualexecutor.builder.{ContextualExecutorBuilder, Parsers}
import com.github.unchama.contextualexecutor.executors.{BranchedExecutor, EchoExecutor}
import com.github.unchama.minecraft.actions.OnMinecraftServerThread
import com.github.unchama.seichiassist.SeichiAssist
import com.github.unchama.seichiassist.SeichiAssist.databaseGateway
import com.github.unchama.seichiassist.commands.contextual.builder.BuilderTemplates.playerCommandBuilder
import com.github.unchama.seichiassist.concurrent.PluginExecutionContexts.onMainThread
import com.github.unchama.seichiassist.data.MineStackGachaData
import com.github.unchama.seichiassist.subsystems.gacha.domain.bukkit.GachaPrize
import com.github.unchama.seichiassist.subsystems.gacha.domain.{
  GachaPersistence,
  GachaPrizeId,
  GachaPrizesDataOperations,
  GachaProbability
}
import com.github.unchama.seichiassist.subsystems.gacha.subsystems.gachaticket.domain.GachaTicketPersistence
import com.github.unchama.seichiassist.subsystems.itemmigration.domain.minecraft.UuidRepository
import com.github.unchama.seichiassist.util.InventoryOperations
import com.github.unchama.targetedeffect.commandsender.{MessageEffect, MessageEffectF}
import com.github.unchama.targetedeffect.{SequentialEffect, TargetedEffect}
import org.bukkit.ChatColor._
import org.bukkit.command.{CommandSender, TabExecutor}

import java.util.UUID
import scala.util.chaining.scalaUtilChainingOps

class GachaCommand[F[
  _
]: OnMinecraftServerThread: NonServerThreadContextShift: Sync: ConcurrentEffect](
  implicit gachaTicketPersistence: GachaTicketPersistence[F],
  gachaPersistence: GachaPersistence[F],
  gachaPrizesDataOperations: GachaPrizesDataOperations[F],
  syncUuidRepository: UuidRepository[SyncIO]
) {

  import cats.implicits._

  private val printDescriptionExecutor = EchoExecutor(
    MessageEffect(
      List(
        s"$YELLOW$BOLD/gachaコマンドの使い方",
        s"$RED/gacha give <all/プレイヤー名> <個数>",
        "ガチャ券配布コマンドです。allを指定で全員に配布(マルチ鯖対応済)",
        s"$RED/gacha get <ID> (<名前>)",
        "指定したガチャリストのIDを入手 (所有者付きにもできます) IDを0に指定するとガチャリンゴを入手できます",
        s"$RED/gacha add <確率>",
        "現在のメインハンドをガチャリストに追加。確率は1.0までで指定",
        s"$RED/gacha addms2 <確率> <名前>",
        "現在のメインハンドをMineStack用ガチャリストに追加。確率は1.0までで指定",
        s"$RED/gacha addms <名前>  <ID>",
        "指定したガチャリストのIDを指定した名前でMineStack用ガチャリストに追加",
        s"$DARK_GRAY※ゲーム内でのみ実行できます",
        s"$RED/gacha list",
        "現在のガチャリストを表示",
        s"$RED/gacha listms",
        "現在のMineStack用ガチャリストを表示",
        s"$RED/gacha remove <番号>",
        "リスト該当番号のガチャ景品を削除",
        s"$RED/gacha removems",
        "リスト一番下のMineStackガチャ景品を削除(追加失敗した場合の修正用)",
        s"$RED/gacha setamount <番号> <個数>",
        "リスト該当番号のガチャ景品の個数変更。64まで",
        s"$RED/gacha setprob <番号> <確率>",
        "リスト該当番号のガチャ景品の確率変更",
        s"$RED/gacha clear",
        "ガチャリストを全消去する。取扱注意",
        s"$RED/gacha save",
        "コマンドによるガチャリストへの変更をmysqlに送信",
        s"$RED/gacha savems",
        "コマンドによるMineStack用ガチャリストへの変更をmysqlに送信",
        s"$DARK_RED※変更したら必ずsaveコマンドを実行(セーブされません)",
        s"$RED/gacha reload",
        "ガチャリストをmysqlから読み込む",
        s"$DARK_GRAY※onEnable時と同じ処理",
        s"$RED/gacha demo <回数>",
        "現在のガチャリストで指定回数試行し結果を表示。100万回まで"
      )
    )
  )

  val executor: TabExecutor = {
    import ChildExecutors._
    BranchedExecutor(
      Map(
        "give" -> giveGachaTickets,
        "get" -> giveItem,
        "add" -> add,
        "remove" -> remove,
        "list" -> list,
        "setamount" -> setamount,
        "setprob" -> setprob,
        "clear" -> clear,
        "save" -> save,
        "reload" -> reload,
        "demo" -> demo,
        "addms" -> addms,
        "addms2" -> addms2,
        "listms" -> listms,
        "removems" -> removems,
        "savems" -> savems
      ),
      whenBranchNotFound = Some(printDescriptionExecutor),
      whenArgInsufficient = Some(printDescriptionExecutor)
    ).asNonBlockingTabExecutor()
  }

  object ChildExecutors {

    val gachaPrizeIdExistsParser: String => Either[TargetedEffect[CommandSender], Any] = Parsers
      .closedRangeInt(1, Int.MaxValue, MessageEffect("IDは正の値を指定してください。"))
      .andThen(_.flatMap { id =>
        val intId = id.asInstanceOf[Int]
        if (
          gachaPrizesDataOperations.existsGachaPrize(GachaPrizeId(intId)).toIO.unsafeRunSync()
        ) {
          succeedWith(intId)
        } else {
          failWith("指定されたIDのアイテムは存在しません！")
        }
      })

    val probParser: String => Either[TargetedEffect[CommandSender], Any] =
      Parsers.double(MessageEffect("確率は小数点数で指定してください。")).andThen {
        _.flatMap { num =>
          val doubleNum = num.asInstanceOf[Double]
          if (doubleNum <= 1.0 && doubleNum >= 0.0) {
            succeedWith(doubleNum)
          } else {
            failWith("確率は正の数かつ1.0以下で指定してください。")
          }
        }
      }

    val giveGachaTickets: ContextualExecutor = ContextualExecutorBuilder
      .beginConfiguration()
      .argumentsParsers(
        List(
          Parsers.fromOptionParser(
            value =>
              value.toLowerCase match {
                case "all" => Some("all")
                case _ =>
                  val uuid = syncUuidRepository.getUuid(value).unsafeRunSync()
                  if (uuid.nonEmpty)
                    uuid.flatMap(uuid => Some(uuid.toString))
                  else None
              },
            MessageEffect("指定されたプレイヤー名が見つかりませんでした。")
          ),
          Parsers.closedRangeInt(1, Int.MaxValue, MessageEffect("配布するガチャ券の枚数は正の値を指定してください。"))
        )
      )
      .executionCSEffect { context =>
        val args = context.args.parsed
        val amount = args(1).asInstanceOf[Int]
        if (args.head.toString == "all") {
          Kleisli
            .liftF(gachaTicketPersistence.add(amount))
            .flatMap(_ => MessageEffectF(s"${GREEN}全プレイヤーへガチャ券${amount}枚加算成功"))
        } else {
          // Parserによりallじゃなかった場合はUUIDであることが確定している
          Kleisli
            .liftF(gachaTicketPersistence.add(amount, UUID.fromString(args.head.toString)))
            .flatMap(_ => MessageEffectF(s"${GREEN}ガチャ券${amount}枚加算成功"))
        }
      }
      .build()

    val giveItem: ContextualExecutor =
      playerCommandBuilder
        .argumentsParsers(List(gachaPrizeIdExistsParser))
        .execution { context =>
          val eff = for {
            gachaPrize <- gachaPrizesDataOperations.getGachaPrize(
              GachaPrizeId(context.args.parsed.head.asInstanceOf[Int])
            )
          } yield SequentialEffect(
            InventoryOperations.grantItemStacksEffect[IO](
              gachaPrize.get.getGiveItemStack(Some(context.sender.getName))
            )
          )

          eff.toIO
        }
        .build()

    val add: ContextualExecutor =
      playerCommandBuilder
        .argumentsParsers(List(probParser))
        .execution { context =>
          val player = context.sender
          val probability = context.args.parsed.head.asInstanceOf[Double]
          val mainHandItem = player.getInventory.getItemInMainHand
          val eff = for {
            _ <- gachaPrizesDataOperations.addGachaPrize(
              GachaPrize(mainHandItem, GachaProbability(probability), probability < 0.1, _)
            )
          } yield MessageEffect(
            List("ガチャアイテムを追加しました！", "ガチャアイテムを永続化するためには/gacha saveを実行してください。")
          )

          eff.toIO
        }
        .build()

    val list: ContextualExecutor =
      ContextualExecutorBuilder
        .beginConfiguration()
        .execution { _ =>
          val eff = for {
            gachaPrizes <- gachaPrizesDataOperations.gachaPrizesList
          } yield {
            val gachaPrizeInformation = gachaPrizes
              .sortBy(_.id.id)
              .map { gachaPrize =>
                val itemStack = gachaPrize.itemStack
                val probability = gachaPrize.probability.value

                s"${gachaPrize.id.id}|${itemStack.getType.toString}/${itemStack
                    .getItemMeta
                    .getDisplayName}$RESET|${itemStack.getAmount}|$probability(${probability * 100}%)"
              }
              .toList

            val totalProbability = gachaPrizes.map(_.probability.value).sum
            MessageEffect(
              List(s"${RED}アイテム番号|アイテム名|アイテム数|出現確率") ++ gachaPrizeInformation ++ List(
                s"${RED}合計確率: $totalProbability(${totalProbability * 100}%)",
                s"${RED}合計確率は100%以内に収まるようにしてください。"
              )
            )
          }

          eff.toIO
        }
        .build()

    val remove: ContextualExecutor = ContextualExecutorBuilder
      .beginConfiguration()
      .argumentsParsers(List(gachaPrizeIdExistsParser))
      .execution { context =>
        val eff = for {
          _ <- gachaPrizesDataOperations.removeByGachaPrizeId(
            GachaPrizeId(context.args.parsed.head.asInstanceOf[Int])
          )
        } yield MessageEffect(
          List("ガチャアイテムを削除しました", "ガチャアイテム削除を永続化するためには/gacha saveを実行してください。")
        )

        eff.toIO

      }
      .build()

    val setamount: ContextualExecutor =
      ContextualExecutorBuilder
        .beginConfiguration()
        .argumentsParsers(
          List(
            gachaPrizeIdExistsParser,
            Parsers.closedRangeInt(1, 64, MessageEffect("数は1～64で指定してください。"))
          )
        )
        .execution { context =>
          val targetId = GachaPrizeId(context.args.parsed.head.asInstanceOf[Int])
          val amount = context.args.parsed(1).asInstanceOf[Int]
          val eff = for {
            existingGachaPrize <- gachaPrizesDataOperations.getGachaPrize(targetId)
            _ <- gachaPrizesDataOperations.removeByGachaPrizeId(targetId)
            itemStack = existingGachaPrize.get.itemStack
            _ <- gachaPrizesDataOperations.addGachaPrize(_ =>
              existingGachaPrize
                .get
                .copy(itemStack = itemStack.tap {
                  _.setAmount(amount)
                })
            )
          } yield MessageEffect(
            s"${targetId.id}|${itemStack.getType.toString}/${itemStack.getItemMeta.getDisplayName}${RESET}のアイテム数を${amount}個に変更しました。"
          )

          eff.toIO
        }
        .build()

    val setprob: ContextualExecutor = ContextualExecutorBuilder
      .beginConfiguration()
      .argumentsParsers(List(gachaPrizeIdExistsParser, probParser))
      .execution { context =>
        val args = context.args.parsed
        val targetId = GachaPrizeId(args.head.asInstanceOf[Int])
        val newProb = args(1).asInstanceOf[Double]
        val eff = for {
          existingGachaPrize <- gachaPrizesDataOperations.getGachaPrize(targetId)
          _ <- gachaPrizesDataOperations.removeByGachaPrizeId(targetId)
          _ <- gachaPrizesDataOperations.addGachaPrize(_ =>
            existingGachaPrize.get.copy(probability = GachaProbability(newProb))
          )
          itemStack = existingGachaPrize.get.itemStack
        } yield MessageEffect(s"${targetId.id}|${itemStack.getType.toString}/${itemStack
            .getItemMeta
            .getDisplayName}${RESET}の確率を$newProb(${newProb * 100}%)に変更しました。")

        eff.toIO
      }
      .build()

    val clear: ContextualExecutor =
      ContextualExecutorBuilder
        .beginConfiguration()
        .execution { _ =>
          val eff = for {
            _ <- gachaPrizesDataOperations.clear()
          } yield MessageEffect(
            List(
              "すべて削除しました。",
              "/gacha saveを実行するとmysqlのデータも全削除されます。",
              "削除を取り消すには/gacha reloadコマンドを実行します。"
            )
          )
          eff.toIO

        }
        .build()

    val save: ContextualExecutor =
      ContextualExecutorBuilder
        .beginConfiguration()
        .execution { _ =>
          val eff = for {
            gachaPrizes <- gachaPrizesDataOperations.gachaPrizesList
            _ <- gachaPersistence.update(gachaPrizes)
          } yield MessageEffect("ガチャデータをmysqlに保存しました。")

          eff.toIO
        }
        .build()

    val reload: ContextualExecutor = ContextualExecutorBuilder
      .beginConfiguration()
      .execution { _ =>
        val eff = for {
          _ <- gachaPrizesDataOperations.loadGachaPrizes(gachaPersistence)
        } yield MessageEffect("ガチャデータをmysqlから読み込みました。")

        eff.toIO
      }
      .build()

    val demo: ContextualExecutor = ContextualExecutorBuilder
      .beginConfiguration()
      .argumentsParsers(
        List(Parsers.closedRangeInt(1, 1000000, MessageEffect("試行回数は1～100万回までで指定してください。")))
      )
      .execution { context =>
        val numberOfTimes = context.args.parsed.head.asInstanceOf[Int]
        var gigantic = 0
        var big = 0
        var regular = 0
        var potato = 0
        (0 to numberOfTimes).foreach { _ =>
          val rand = Math.random()
          if (rand < 0.001) gigantic += 1
          else if (rand < 0.01) big += 1
          else if (rand < 0.1) regular += 1
          else potato += 1
        }

        IO(
          MessageEffect(
            List(
              s"$AQUA${BOLD}ガチャ${numberOfTimes}回試行結果",
              s"ギガンティック:${gigantic}回(${gigantic.asInstanceOf[Double] / numberOfTimes * 100.0}%)",
              s"大当たり:${big}回(${big.asInstanceOf[Double] / numberOfTimes * 100.0}%)",
              s"あたり:${regular}回(${regular.asInstanceOf[Double] / numberOfTimes * 100.0}%)",
              s"ハズレ:${potato}回(${potato.asInstanceOf[Double] / numberOfTimes * 100.0}%)"
            )
          )
        )
      }
      .build()

    // TODO: ここから下のコマンドの実装はMineStackシステムがレガシーのときに行われているため、旧実装をそのままなぞらえて実装している。
    //  そのため、MineStackシステムがsubsystemsに含まれるときが来たら書き換えることが望ましい。
    //  というかそもそもこの実装はMineStack側で行われるべきであるかもしれない。

    val addms: ContextualExecutor = ContextualExecutorBuilder
      .beginConfiguration()
      .argumentsParsers(List(Parsers.identity, gachaPrizeIdExistsParser))
      .execution { context =>
        val args = context.args.parsed
        val eff = for {
          gachaPrize <- gachaPrizesDataOperations.getGachaPrize(
            GachaPrizeId(args(1).asInstanceOf[Int])
          )
        } yield {
          val _gachaPrize = gachaPrize.get // ParserによりGachaPrizeの存在は確認されている
          val mineStackGachaData = new MineStackGachaData(
            args.head.toString,
            _gachaPrize.itemStack,
            _gachaPrize.probability.value,
            1
          )
          SeichiAssist.msgachadatalist.addOne(mineStackGachaData)
          MessageEffect(
            List(
              s"ガチャID:${_gachaPrize.id}のデータを変数名:${args.head.toString}でMineStack用ガチャリストに追加しました。",
              "/gacha savemsでmysqlに保存してください"
            )
          )
        }

        eff.toIO
      }
      .build()

    val addms2: ContextualExecutor =
      playerCommandBuilder
        .argumentsParsers(List(probParser, Parsers.identity))
        .execution { context =>
          val args = context.args.parsed
          val mainHand = context.sender.getInventory.getItemInMainHand
          val probability = args.head.asInstanceOf[Double]
          val mineStackGachaData =
            new MineStackGachaData(args(1).toString, mainHand, probability, 1)
          SeichiAssist.msgachadatalist.addOne(mineStackGachaData)
          IO(
            MessageEffect(
              List(
                s"${mainHand.getType.toString}/${mainHand.getItemMeta.getDisplayName}$RESET${mainHand.getAmount}個を確率${probability}としてMineStack用ガチャリストに追加しました。",
                "/gacha savemsでmysqlに保存してください。"
              )
            )
          )
        }
        .build()

    val listms: ContextualExecutor =
      ContextualExecutorBuilder
        .beginConfiguration()
        .execution { _ =>
          IO {
            val gachaDataListInformation = SeichiAssist.msgachadatalist.zipWithIndex.map {
              case (gachaData, index) =>
                s"$index|${gachaData.level}|${gachaData.objName}|${gachaData.itemStack.getType.toString}/${gachaData.itemStack.getItemMeta.getDisplayName}$RESET|${gachaData
                    .itemStack
                    .getAmount}|${gachaData.probability}(${gachaData.probability * 100}%)"
            }
            MessageEffect(
              List(s"${RED}アイテム番号|レベル|変数名|アイテム名|アイテム数|出現確率") ++ gachaDataListInformation
            )
          }
        }
        .build()

    val removems: ContextualExecutor =
      ContextualExecutorBuilder
        .beginConfiguration()
        .execution { _ =>
          IO {
            if (SeichiAssist.msgachadatalist.isEmpty) {
              MessageEffect("MineStack用ガチャリストが空です。")
            } else {
              val size = SeichiAssist.msgachadatalist.size
              val mineStackGachaData = SeichiAssist.msgachadatalist(size - 1)
              SeichiAssist.msgachadatalist.remove(size - 1)
              MessageEffect(
                List(
                  s"$size|${mineStackGachaData.level}|${mineStackGachaData.objName}|${mineStackGachaData.itemStack.getType.toString}/${mineStackGachaData
                      .itemStack
                      .getItemMeta
                      .getDisplayName}$RESET|${mineStackGachaData.itemStack.getAmount}|${mineStackGachaData.probability}を削除しました。",
                  "/gacha savemsでmysqlに保存してください。"
                )
              )
            }
          }
        }
        .build()

    val savems: ContextualExecutor =
      ContextualExecutorBuilder
        .beginConfiguration()
        .execution { _ =>
          IO {
            if (!databaseGateway.mineStackGachaDataManipulator.saveMineStackGachaData)
              MessageEffect("mysqlにMineStack用ガチャデータを保存できませんでした")
            else MessageEffect("mysqlにMineStack用ガチャデータを保存しました")
          }
        }
        .build()

  }

}
