package dev.lip.keyboard

/** Exact top-two prefix range ranking. Built on the worker once per lexicon, never a per-key scan. */
internal class TypingPrefixIndex(entries:List<SwipeLexeme>){
    private val keys:List<String>
    private val frequencies:IntArray
    private val size:Int
    private val tree:IntArray
    init{
        require(entries.size<=SwipeAssetCatalog.MAX_ENTRIES)
        val sorted=entries.sortedBy{it.word}
        keys=sorted.map{it.word};frequencies=IntArray(sorted.size){sorted[it].frequency}
        require(keys.zipWithNext().all{(a,b)->a<b})
        var capacity=1;while(capacity<keys.size)capacity=capacity shl 1
        size=capacity;tree=IntArray(size*2){-1}
        for(i in keys.indices)tree[size+i]=i
        for(i in size-1 downTo 1)tree[i]=better(tree[i*2],tree[i*2+1])
    }
    private fun better(a:Int,b:Int):Int=when{
        a<0->b;b<0->a;frequencies[a]>frequencies[b]->a;frequencies[b]>frequencies[a]->b;else->minOf(a,b)
    }
    private fun lowerBound(value:String):Int{
        var low=0;var high=keys.size
        while(low<high){val mid=(low+high) ushr 1;if(keys[mid]<value)low=mid+1 else high=mid}
        return low
    }
    private fun maximum(from:Int,until:Int):Int{
        var left=from+size;var right=until+size;var best=-1
        while(left<right){
            if(left and 1!=0)best=better(best,tree[left++])
            if(right and 1!=0)best=better(best,tree[--right])
            left=left ushr 1;right=right ushr 1
        }
        return best
    }
    fun topTwo(prefix:String):List<String>{
        require(prefix.length<=48&&prefix.all{it in 'a'..'z'||it=='\''})
        var from=lowerBound(prefix);val until=lowerBound(prefix+'\uffff')
        if(from<until&&keys[from]==prefix)from++ // Current spelling is already the original candidate.
        val first=maximum(from,until);if(first<0)return emptyList()
        val second=better(maximum(from,first),maximum(first+1,until))
        return if(second<0)listOf(keys[first]) else listOf(keys[first],keys[second])
    }
}
