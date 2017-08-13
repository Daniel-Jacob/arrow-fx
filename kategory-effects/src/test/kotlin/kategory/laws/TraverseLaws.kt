package kategory

import io.kotlintest.properties.forAll

typealias TI<A> = Tuple2<IdKind<A>, IdKind<A>>

typealias TIK<A> = HK<TIF, A>

@Suppress("UNCHECKED_CAST")
fun <A> TIK<A>.ev(): TIC<A> =
        this as TIC<A>

data class TIC<out A>(val ti: TI<A>) : TIK<A>

class TIF {
    private constructor()
}

object TraverseLaws {
    // FIXME(paco): cf is only required because AP::pure will crash the inliner
    inline fun <reified F> laws(FF: Traverse<F> = traverse<F>(), AP: Applicative<F> = applicative<F>(), crossinline cf: (Int) -> HK<F, Int>, EQ: Eq<HK<F, Int>>): List<Law> =
            FoldableLaws.laws(FF, cf, Eq.any()) + FunctorLaws.laws(AP, EQ) + listOf(
                    Law("Traverse Laws: Identity", { identityTraverse(FF, AP, cf, EQ) }),
                    Law("Traverse Laws: Sequential composition", { sequentialComposition(FF, cf, EQ) }),
                    Law("Traverse Laws: Parallel composition", { parallelComposition(FF, cf, EQ) }),
                    Law("Traverse Laws: FoldMap derived", { foldMapDerived(FF, AP, cf, EQ) })
            )

    inline fun <reified F> identityTraverse(FF: Traverse<F>, AP: Applicative<F> = applicative<F>(), crossinline cf: (Int) -> HK<F, Int>, EQ: Eq<HK<F, Int>>) =
            forAll(genFunctionAToB<Int, HK<IdHK, Int>>(genConstructor(genIntSmall(), ::Id)), genConstructor(genIntSmall(), cf), { f: (Int) -> HK<IdHK, Int>, fa: HK<F, Int> ->
                FF.traverse(fa, f, Id).value().equalUnderTheLaw(FF.map(fa, f).map(AP) { it.value() }, EQ)
            })

    inline fun <reified F> sequentialComposition(FF: Traverse<F>, crossinline cf: (Int) -> HK<F, Int>, EQ: Eq<HK<F, Int>>) =
            forAll(genFunctionAToB<Int, HK<IdHK, Int>>(genConstructor(genIntSmall(), ::Id)), genFunctionAToB<Int, HK<IdHK, Int>>(genConstructor(genIntSmall(), ::Id)), genConstructor(genIntSmall(), cf), { f: (Int) -> HK<IdHK, Int>, g: (Int) -> HK<IdHK, Int>, fha: HK<F, Int> ->
                val fa = fha.traverse(FF, Id, f).ev()
                val composed = Id.map(fa, { it.traverse(FF, Id, g) }).value.value()
                val expected = fha.traverse(FF, ComposedApplicative(Id, Id), { a: Int -> Id.map(f(a), g).lift() }).lower().value().value()
                composed.equalUnderTheLaw(expected, EQ)
            })

    inline fun <reified F> parallelComposition(FF: Traverse<F>, crossinline cf: (Int) -> HK<F, Int>, EQ: Eq<HK<F, Int>>) =
            forAll(genFunctionAToB<Int, HK<IdHK, Int>>(genConstructor(genIntSmall(), ::Id)), genFunctionAToB<Int, HK<IdHK, Int>>(genConstructor(genIntSmall(), ::Id)), genConstructor(genIntSmall(), cf), { f: (Int) -> HK<IdHK, Int>, g: (Int) -> HK<IdHK, Int>, fha: HK<F, Int> ->
                val TIA = object : Applicative<TIF> {
                    override fun <A> pure(a: A): HK<TIF, A> =
                            TIC(Id(a) toT Id(a))


                    override fun <A, B> ap(fa: HK<TIF, A>, ff: HK<TIF, (A) -> B>): HK<TIF, B> {
                        val (fam, fan) = fa.ev().ti
                        val (fm, fn) = ff.ev().ti
                        return TIC(Id.ap(fam, fm) toT Id.ap(fan, fn))
                    }

                }

                val TIEQ: Eq<TI<HK<F, Int>>> = object : Eq<TI<HK<F, Int>>> {
                    override fun eqv(a: TI<HK<F, Int>>, b: TI<HK<F, Int>>): Boolean =
                            EQ.eqv(a.a.value(), b.a.value()) && EQ.eqv(a.b.value(), b.b.value())
                }

                val seen: TI<HK<F, Int>> = FF.traverse(fha, { TIC(f(it) toT g(it)) }, TIA).ev().ti
                val expected: TI<HK<F, Int>> = TIC(FF.traverse(fha, f, Id) toT FF.traverse(fha, g, Id)).ti

                seen.equalUnderTheLaw(expected, TIEQ)
            })

    inline fun <reified F> foldMapDerived(FF: Traverse<F>, AP: Applicative<F> = applicative<F>(), crossinline cf: (Int) -> HK<F, Int>, EQ: Eq<HK<F, Int>>) =
            forAll(genFunctionAToB<Int, Int>(genIntSmall()), genConstructor(genIntSmall(), cf), { f: (Int) -> Int, fa: HK<F, Int> ->
                val traversed = fa.traverse(FF, Const.applicative(IntMonoid), { a -> f(a).const() }).value()
                val mapped = fa.foldMap(FF, IntMonoid, f)
                mapped.equalUnderTheLaw(traversed, Eq.any())
            })
}