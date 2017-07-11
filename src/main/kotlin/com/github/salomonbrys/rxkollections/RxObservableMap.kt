package com.github.salomonbrys.rxkollections

import io.reactivex.Observable
import io.reactivex.rxkotlin.cast
import io.reactivex.rxkotlin.toObservable
import io.reactivex.subjects.PublishSubject
import java.util.*
import java.util.function.BiConsumer
import java.util.function.Consumer
import java.util.stream.Stream

interface RxObservableMap<K: Any, out V: Any> : Map<K, V> {
    val observable: Observable<out Operation<K, V>>

    fun observableOf(): Observable<out Operation<K, V>>

    fun observableOf(key: K): Observable<out Operation<K, V>>

    fun observableOf(filter: (K) -> Boolean): Observable<out Operation<K, V>>

    @Suppress("unused")
    sealed class Operation<out K: Any, out V: Any> {
        abstract val key: K
        sealed class Put<out K: Any, out V: Any> : Operation<K, V>(), Map.Entry<K, V> {
            data class Add<out K: Any, out V: Any>(override val key: K, override val value: V) : Put<K, V>()
            data class Update<out K: Any, out V: Any>(override val key: K, val oldValue: V, override val value: V) : Put<K, V>()
        }
        data class Remove<out K: Any, out V: Any>(override val key: K, val oldValue: V) : Operation<K, V>()
    }
}

@Suppress("ReplaceGetOrSet")
// Not using delegation anywhere to be sure Java 8 enhancements (such as Spliterators) do use the default fallback.
class RxObservableMutableMap<K: Any, V: Any>(private val _map: MutableMap<K, V>) : MutableMap<K, V>, RxObservableMap<K, V> {

    constructor() : this(HashMap())

    private inner class _ImmutableWrapper : RxObservableMap<K, V> by this@RxObservableMutableMap
    fun asImmutable(): RxObservableMap<K, V> = _ImmutableWrapper()

    private val _subject = PublishSubject.create<RxObservableMap.Operation<K, V>>()
    override val observable: Observable<RxObservableMap.Operation<K, V>> get() = _subject.hide()

    override fun observableOf(): Observable<RxObservableMap.Operation<K, V>>
            = observable
                    .startWith(
                            _map.entries.asSequence()
                                    .map { RxObservableMap.Operation.Put.Add(it.key, it.value) }
                                    .toObservable()
                                    .cast()
                    )

    override fun observableOf(key: K): Observable<RxObservableMap.Operation<K, V>> {
        val o = observable.filter { it.key == key }
        val value = get(key)
        if (value != null)
            return o.startWith(RxObservableMap.Operation.Put.Add(key, value))
        else
            return o
    }

    override fun observableOf(filter: (K) -> Boolean): Observable<RxObservableMap.Operation<K, V>> {
        return observable
                .filter { filter(it.key) }
                .startWith(
                        _map.entries.toObservable()
                                .filter { filter(it.key) }
                                .map { RxObservableMap.Operation.Put.Add(it.key, it.value) }
                                .cast()
                )
    }

    private inner class _Keys(private val _set: MutableSet<K>) : MutableSet<K> {
        private inner class _Iterator(private val _it: MutableIterator<K>) : MutableIterator<K> {
            private var _cur: K? = null

            override fun hasNext() = _it.hasNext()

            override fun next(): K {
                val next = _it.next()
                _cur = next
                return next
            }

            override fun remove() {
                val key = _cur ?: throw IllegalStateException()
                val oldValue = _map[key] ?: throw IllegalStateException()
                _it.remove()
                _subject.onNext(RxObservableMap.Operation.Remove(key, oldValue))
            }

            override fun forEachRemaining(action: Consumer<in K>) = _it.forEachRemaining(action)
        }

        override fun iterator(): MutableIterator<K> = _Iterator(_set.iterator())

        override fun clear() {
            val map = HashMap(_map)
            _set.clear()
            map.forEach { _subject.onNext(RxObservableMap.Operation.Remove(it.key, it.value)) }
        }

        override fun remove(element: K): Boolean {
            val oldValue = _map[element]
            val removed = _set.remove(element)
            if (removed) {
                oldValue ?: throw IllegalStateException()
                _subject.onNext(RxObservableMap.Operation.Remove(element, oldValue))
            }
            return removed
        }

        override fun removeAll(elements: Collection<K>): Boolean {
            val map = HashMap(_map)
            val changed = _set.removeAll(elements)
            if (changed)
                map.keys.union(elements).forEach { _subject.onNext(RxObservableMap.Operation.Remove(it, map[it]!!)) }
            return changed
        }

        override fun retainAll(elements: Collection<K>): Boolean {
            val map = HashMap(_map)
            val changed = _set.retainAll(elements)
            if (changed)
                map.keys.subtract(elements).forEach { _subject.onNext(RxObservableMap.Operation.Remove(it, map[it]!!)) }
            return changed
        }

        override fun add(element: K) = throw UnsupportedOperationException()
        override fun addAll(elements: Collection<K>) = throw UnsupportedOperationException()
        override val size: Int get() = _set.size
        override fun contains(element: K) = _set.contains(element)
        override fun containsAll(elements: Collection<K>) = _set.containsAll(elements)
        override fun isEmpty() = _set.isEmpty()
        override fun parallelStream(): Stream<K> = _set.parallelStream()
        override fun spliterator(): Spliterator<K> = _set.spliterator()
        override fun stream(): Stream<K> = _set.stream()
        override fun forEach(action: Consumer<in K>?) = _set.forEach(action)
    }

    override val keys: MutableSet<K> get() = _Keys(_map.keys)

    private inner class _Values : AbstractCollection<V>() {
        override val size: Int get() = _map.size
        override operator fun contains(element: V) = _map.containsValue(element)
        override fun spliterator(): Spliterator<V> = _map.values.spliterator()
        override fun forEach(action: Consumer<in V>) = _map.values.forEach(action)
        override fun isEmpty() = _map.values.isEmpty()
        override fun containsAll(elements: Collection<V>) = _map.values.containsAll(elements)
        override fun parallelStream(): Stream<V> = _map.values.parallelStream()
        override fun stream(): Stream<V> = _map.values.stream()

        override fun clear() = this@RxObservableMutableMap.clear()

        inner class _Iterator(private val _it: MutableIterator<Map.Entry<K, V>>) : MutableIterator<V> {
            override fun hasNext() = _it.hasNext()
            override fun next() = _it.next().value
            override fun remove() = _it.remove()
        }

        override fun iterator(): MutableIterator<V> = _Iterator(this@RxObservableMutableMap.entries.iterator())
    }

    override val values: MutableCollection<V> get() = _Values()

    private inner class _Entries(private val _set: MutableSet<MutableMap.MutableEntry<K, V>>) : MutableSet<MutableMap.MutableEntry<K, V>> {

        private inner class _Entry(private val _entry: MutableMap.MutableEntry<K, V>) : MutableMap.MutableEntry<K, V> {
            override val key: K get() = _entry.key
            override val value: V get() = _entry.value

            override fun setValue(newValue: V): V {
                val oldValue = _entry.setValue(newValue)
                _subject.onNext(RxObservableMap.Operation.Put.Update(key, oldValue, newValue))
                return oldValue
            }
        }

        private inner class _Iterator(private val _it: MutableIterator<MutableMap.MutableEntry<K, V>>) : MutableIterator<MutableMap.MutableEntry<K, V>> {
            override fun hasNext() = _it.hasNext()

            private var _entry: Map.Entry<K, V>? = null

            override fun next(): MutableMap.MutableEntry<K, V> {
                val next = _it.next()
                _entry = next
                return _Entry(next)
            }

            override fun remove() {
                val entry = _entry ?: throw IllegalStateException()
                _it.remove()
                _subject.onNext(RxObservableMap.Operation.Remove(entry.key, entry.value))
            }
        }

        override fun iterator(): MutableIterator<MutableMap.MutableEntry<K, V>> = _Iterator(_set.iterator())

        override fun clear() {
            val set = HashSet(_set)
            _set.clear()
            set.forEach { _subject.onNext(RxObservableMap.Operation.Remove(it.key, it.value)) }
        }

        override fun remove(element: MutableMap.MutableEntry<K, V>): Boolean {
            val removed = _set.remove(element)
            if (removed)
                _subject.onNext(RxObservableMap.Operation.Remove(element.key, element.value))
            return removed
        }

        override fun removeAll(elements: Collection<MutableMap.MutableEntry<K, V>>): Boolean {
            val set = HashSet(_set)
            val changed = _set.removeAll(elements)
            if (changed)
                set.union(elements).forEach { _subject.onNext(RxObservableMap.Operation.Remove(it.key, it.value)) }
            return changed
        }

        override fun retainAll(elements: Collection<MutableMap.MutableEntry<K, V>>): Boolean {
            val set = HashSet(_set)
            val changed = _set.retainAll(elements)
            if (changed)
                set.subtract(elements).forEach { _subject.onNext(RxObservableMap.Operation.Remove(it.key, it.value)) }
            return changed
        }

        override fun add(element: MutableMap.MutableEntry<K, V>) = throw UnsupportedOperationException()
        override fun addAll(elements: Collection<MutableMap.MutableEntry<K, V>>) = throw UnsupportedOperationException()
        override val size: Int get() = _set.size
        override fun contains(element: MutableMap.MutableEntry<K, V>) = _set.contains(element)
        override fun containsAll(elements: Collection<MutableMap.MutableEntry<K, V>>) = _set.containsAll(elements)
        override fun isEmpty() = _set.isEmpty()
    }

    override val entries: MutableSet<MutableMap.MutableEntry<K, V>> get() = _Entries(_map.entries)

    override fun clear() {
        val map = HashMap(_map)
        _map.clear()
        map.forEach { _subject.onNext(RxObservableMap.Operation.Remove(it.key, it.value)) }
    }

    override fun put(key: K, value: V): V? {
        val oldValue = _map.put(key, value)
        _subject.onNext(when(oldValue) {
            null -> RxObservableMap.Operation.Put.Add(key, value)
            else -> RxObservableMap.Operation.Put.Update(key, oldValue, value)
        })
        return oldValue
    }

    override fun putAll(from: Map<out K, V>) {
        val map = HashMap(_map)
        _map.putAll(from)
        from.entries.forEach {
            val oldValue = map[it.key]
            _subject.onNext(when (oldValue) {
                null -> RxObservableMap.Operation.Put.Add(it.key, it.value)
                else -> RxObservableMap.Operation.Put.Update(it.key, oldValue, it.value)
            })
        }
    }

    override fun remove(key: K): V? {
        val oldValue = _map.remove(key)
        if (oldValue != null)
            _subject.onNext(RxObservableMap.Operation.Remove(key, oldValue))
        return oldValue
    }

    override val size: Int get() = _map.size
    override fun containsKey(key: K) = _map.containsKey(key)
    override fun containsValue(value: V) = _map.containsValue(value)
    override fun get(key: K) = _map.get(key)
    override fun isEmpty() = _map.isEmpty()
    override fun forEach(action: BiConsumer<in K, in V>) = _map.forEach(action)
}
