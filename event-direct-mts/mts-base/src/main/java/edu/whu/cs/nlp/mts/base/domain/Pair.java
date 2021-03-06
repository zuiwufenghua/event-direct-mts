package edu.whu.cs.nlp.mts.base.domain;

/**
 * Pair类型
 *
 * @author ZhenchaoWang 2015-11-18 20:48:32
 *
 * @param <L>
 * @param <R>
 */
public class Pair<L, R> {

    private final L left;

    private final R right;

    public Pair(L left, R right) {
        super();
        this.left = left;
        this.right = right;
    }

    public L getLeft() {
        return this.left;
    }

    public R getRight() {
        return this.right;
    }

}
