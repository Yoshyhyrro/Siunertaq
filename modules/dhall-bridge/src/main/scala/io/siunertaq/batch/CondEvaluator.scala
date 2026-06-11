package io.siunertaq.batch

/** JCL COND文の評価ロジック。
 *
 *  JCL意味論:
 *    COND=(threshold, op): threshold op previousRC が「真」なら当ステップをスキップ
 *    COND=EVEN:  ABENDがあっても必ず実行 (絶対スキップしない)
 *    COND=ONLY:  いずれかの前ステップがABENDした場合のみ実行
 *
 *  例:
 *    COND=(4,LT) → "4 < prevRC" が真 (prevRC>4) ならスキップ
 *    COND=(0,NE) → "0 ≠ prevRC" が真 (prevRC≠0) ならスキップ
 */
object CondEvaluator:

  /** @param cond    Dhallから読み込んだCOND式
   *  @param maxRC   前ステップ群の最大リターンコード
   *  @param abended いずれかの前ステップがABENDしたか
   *  @return true → 当ステップをスキップ
   */
  def shouldSkip(cond: Option[CondExpr], maxRC: Int, abended: Boolean): Boolean =
    cond match
      case None                           => false       // CONDなし → 常に実行
      case Some(CondExpr.Even)            => false       // EVEN → 絶対実行
      case Some(CondExpr.Only)            => !abended    // ONLY → ABENDなければスキップ
      case Some(CondExpr.Compare(t, op))  =>
        op match
          case CondOp.LT => t < maxRC
          case CondOp.LE => t <= maxRC
          case CondOp.EQ => t == maxRC
          case CondOp.NE => t != maxRC
          case CondOp.GT => t > maxRC
          case CondOp.GE => t >= maxRC

  /** Spring Batch ExitStatus.exitCode → JCL リターンコード変換 */
  def exitStatusToRC(exitCode: String): Int = exitCode match
    case "COMPLETED"  => 0
    case "NOOP"       => 0
    case "STOPPED"    => 8
    case "FAILED"     => 12
    case "UNKNOWN"    => 16
    case _            => 4    // WARNING相当