package com.github.salomonbrys.rxkollections

import io.reactivex.Observable
import io.reactivex.rxkotlin.cast
import io.reactivex.rxkotlin.toObservable
import io.reactivex.subjects.PublishSubject

interface RxObservableCollection<out E: Any> : Collection<E> {
    val observable: Observable<out Operation<E>>

    @Suppress("UNCHECKED_CAST")
    fun observableOf(): Observable<out Operation<E>>
            = (observable as Observable<Operation<E>>)
                    .startWith(
                            this.asSequence()
                                    .map { e -> Operation.Put(e) }
                                    .toObservable()
                                    .cast()
                    )

    @Suppress("unused")
    sealed class Operation<out E: Any> {
        data class Put<out E: Any>(val element: E) : Operation<E>()
        data class Remove<out E: Any>(val oldElement: E) : Operation<E>()
    }
}

// Not using delegation anywhere to be sure Java 8 enhancements (such as Spliterators) do use the default fallback.
class RxObservableMutableCollection<E: Any> (private val _col: MutableCollection<E>) : MutableCollection<E>, RxObservableCollection<E> {

    private val _subject = PublishSubject.create<RxObservableCollection.Operation<E>>()
    override val observable: Observable<RxObservableCollection.Operation<E>> get() = _subject.hide()

    private inner class _Iterator(private val _it: MutableIterator<E>) : MutableIterator<E> {

        private var _curElement: E? = null

        override fun next(): E {
            val element = _it.next()
            _curElement = element
            return element
        }

        override fun remove() {
            _it.remove()
            val element = _curElement
            if (element != null)
                _subject.onNext(RxObservableCollection.Operation.Remove(element))
        }

        override fun hasNext() = _it.hasNext()
    }

    override fun iterator(): MutableIterator<E> = _Iterator(_col.iterator())

    override fun add(element: E): Boolean {
        val mod = _col.add(element)
        if (mod)
            _subject.onNext(RxObservableCollection.Operation.Put(element))
        return mod
    }

    override fun remove(element: E): Boolean {
        val mod = _col.remove(element)
        if (mod)
            _subject.onNext(RxObservableCollection.Operation.Remove(element))
        return mod
    }

    override fun clear() {
        val col = ArrayList(_col)
        _col.clear()
        col.forEach {
            _subject.onNext(RxObservableCollection.Operation.Remove(it))
        }
    }

    override fun addAll(elements: Collection<E>): Boolean {
        val col = ArrayList(_col)
        val mod = _col.addAll(elements)
        if (mod)
            elements.forEach {
                if (it !in col)
                    _subject.onNext(RxObservableCollection.Operation.Put(it))
            }
        return mod
    }

    override fun removeAll(elements: Collection<E>): Boolean {
        val col = ArrayList(_col)
        val mod = _col.removeAll(elements)
        if (mod)
            elements.forEach {
                if (it in col)
                    _subject.onNext(RxObservableCollection.Operation.Remove(it))
            }
        return mod
    }

    override fun retainAll(elements: Collection<E>): Boolean {
        val col = ArrayList(_col)
        val mod = _col.retainAll(elements)
        if (mod)
            col.forEach {
                if (it !in elements)
                    _subject.onNext(RxObservableCollection.Operation.Remove(it))
            }
        return mod
    }

    override val size: Int get() = _col.size
    override fun contains(element: E) = _col.contains(element)
    override fun containsAll(elements: Collection<E>) = _col.containsAll(elements)
    override fun isEmpty() = _col.isEmpty()
}
