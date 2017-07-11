package com.github.salomonbrys.rxkollections

import io.reactivex.Observable
import io.reactivex.rxkotlin.cast
import io.reactivex.rxkotlin.toObservable
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject

interface RxObservableList<out E: Any> : List<E>, RxObservableCollection<E> {
    val listObservable: Observable<out Operation<E>>

    fun listObservableOf(): Observable<out Operation<E>>

    @Suppress("unused")
    sealed class Operation<out E: Any> {
        abstract val index: Int
        sealed class Put<out E: Any> : Operation<E>() {
            abstract val element: E
            data class Add<out E: Any>(override val index: Int, val atEnd: Boolean, override val element: E) : Put<E>()
            data class Update<out E: Any>(override val index: Int, val oldElement: E, override val element: E) : Put<E>()
        }
        data class Remove<out E: Any>(override val index: Int, val oldElement: E) : Operation<E>()
    }

    override val observable: Observable<out RxObservableCollection.Operation<E>>
        get() = listObservable.flatMap {
            when (it) {
                is Operation.Put -> when (it) {
                    is Operation.Put.Add    -> Observable.just(RxObservableCollection.Operation.Put(it.element))
                    is Operation.Put.Update -> Observable.just(RxObservableCollection.Operation.Remove(it.oldElement), RxObservableCollection.Operation.Put(it.element))
                }
                is Operation.Remove         -> Observable.just(RxObservableCollection.Operation.Remove(it.oldElement))
            }
        }
}

// Not using delegation anywhere to be sure Java 8 enhancements (such as Spliterators) do use the default fallback.
class RxObservableMutableList<E: Any> private constructor(
        private val _list: MutableList<E>,
        private val _subject: Subject<RxObservableList.Operation<E>>,
        private val _offset: Int,
        private val _endIsEnd: Boolean
) : MutableList<E>, RxObservableList<E> {

    constructor(list: MutableList<E>) : this(list, PublishSubject.create(), 0, true)

    constructor() : this(ArrayList())

    private inner class _ImmutableWrapper : RxObservableList<E> by this@RxObservableMutableList
    fun asImmutable(): RxObservableList<E> = _ImmutableWrapper()

    override val listObservable: Observable<RxObservableList.Operation<E>> get() = _subject.hide()

    override fun listObservableOf(): Observable<out RxObservableList.Operation<E>>
            = listObservable
                    .startWith(
                            _list.asSequence()
                                    .mapIndexed { i, e -> RxObservableList.Operation.Put.Add(i, _endIsEnd, e) }
                                    .toObservable()
                                    .cast()
                    )

    private inner class _Iterator(
            private val _it: MutableListIterator<E>,
            private var _curIndex: Int
    ) : MutableListIterator<E> {

        private var _curElement: E? = null

        override fun next(): E {
            _curIndex = _it.nextIndex()
            val element = _it.next()
            _curElement = element
            return element
        }

        override fun previous(): E {
            _curIndex = _it.previousIndex()
            val element = _it.previous()
            _curElement = element
            return element
        }

        override fun add(element: E) {
            val atEnd = _it.hasNext()
            _it.add(element)
            _subject.onNext(RxObservableList.Operation.Put.Add(_offset + _curIndex + 1, _endIsEnd && atEnd, element))
        }

        override fun remove() {
            _it.remove()
            val oldElement = _curElement
            if (oldElement != null)
                _subject.onNext(RxObservableList.Operation.Remove(_offset + _curIndex, oldElement))
        }

        override fun set(element: E) {
            val oldElement = _curElement
            _it.set(element)
            _curElement = element
            if (oldElement != null)
                _subject.onNext(RxObservableList.Operation.Put.Update(_offset + _curIndex, oldElement, element))
        }

        override fun nextIndex() = _it.nextIndex()
        override fun previousIndex() = _it.previousIndex()
        override fun hasNext() = _it.hasNext()
        override fun hasPrevious() = _it.hasPrevious()
    }

    override fun iterator(): MutableIterator<E> = _Iterator(_list.listIterator(), -1)
    override fun listIterator(): MutableListIterator<E> = _Iterator(_list.listIterator(), -1)
    override fun listIterator(index: Int): MutableListIterator<E> = _Iterator(_list.listIterator(), index - 1)

    override fun add(element: E): Boolean {
        val index = _list.size
        _list.add(element)
        _subject.onNext(RxObservableList.Operation.Put.Add(_offset + index, _endIsEnd, element))
        return true
    }

    override fun add(index: Int, element: E) {
        val atEnd = index == _list.size
        _list.add(index, element)
        _subject.onNext(RxObservableList.Operation.Put.Add(_offset + index, _endIsEnd && atEnd, element))
    }

    override fun addAll(elements: Collection<E>): Boolean {
        val index = _list.size
        val mod = _list.addAll(index, elements)
        if (mod)
            elements.forEachIndexed { i, e ->
                _subject.onNext(RxObservableList.Operation.Put.Add(_offset + index + i, _endIsEnd, e))
            }
        return mod
    }

    override fun addAll(index: Int, elements: Collection<E>): Boolean {
        val atEnd = index == _list.size
        val mod = _list.addAll(index, elements)
        if (mod)
            elements.forEachIndexed { i, e ->
                _subject.onNext(RxObservableList.Operation.Put.Add(_offset + index + i, _endIsEnd && atEnd, e))
            }
        return mod
    }

    override fun clear() {
        val list = ArrayList(_list)
        _list.clear()
        list.forEachIndexed { i, e -> _subject.onNext(RxObservableList.Operation.Remove(_offset + i, e)) }
    }

    override fun removeAll(elements: Collection<E>): Boolean {
        val list = ArrayList(_list)
        val mod = _list.removeAll(elements)
        if (mod)
            list.forEachIndexed { i, e ->
                if (elements.contains(e))
                    _subject.onNext(RxObservableList.Operation.Remove(_offset + i, e))
            }
        return mod
    }

    override fun retainAll(elements: Collection<E>): Boolean {
        val list = ArrayList(_list)
        val mod = _list.retainAll(elements)
        if (mod)
            list.forEachIndexed { i, e ->
                if (!elements.contains(e))
                    _subject.onNext(RxObservableList.Operation.Remove(_offset + i, e))
            }
        return mod
    }

    override fun removeAt(index: Int): E {
        val oldElement = _list.removeAt(index)
        _subject.onNext(RxObservableList.Operation.Remove(_offset + index, oldElement))
        return oldElement
    }

    override fun remove(element: E): Boolean {
        val index = _list.indexOf(element)
        val mod = _list.remove(element)
        if (mod)
            _subject.onNext(RxObservableList.Operation.Remove(_offset + index, element))
        return mod
    }

    override fun set(index: Int, element: E): E {
        val oldElement = _list.set(index, element)
        _subject.onNext(RxObservableList.Operation.Put.Update(_offset + index, oldElement, element))
        return oldElement
    }

    override fun subList(fromIndex: Int, toIndex: Int): MutableList<E> {
        val atEnd = toIndex == _list.size

        return RxObservableMutableList(_list.subList(fromIndex, toIndex), _subject, fromIndex, atEnd)
    }

    override fun isEmpty() = _list.isEmpty()
    override val size: Int get() = _list.size
    override fun contains(element: E) = _list.contains(element)
    override fun containsAll(elements: Collection<E>) = _list.containsAll(elements)
    override fun get(index: Int) = _list.get(index)
    override fun indexOf(element: E) = _list.indexOf(element)
    override fun lastIndexOf(element: E) = _list.lastIndexOf(element)
}
