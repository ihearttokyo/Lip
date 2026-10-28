package dev.lip.keyboard

/** One explicit delimiter belongs to the verified word. Never synthesize spacing. */
object TypingBoundary {
    private val word=Regex("[A-Za-z]+(?:'[A-Za-z]+)*'?")
    fun isDelimiter(value:String)=value.length==1&&value[0] in " .,!?;:"
    fun suffix(text:String)=text.takeLast(1).takeIf(::isDelimiter)?:""
    fun token(text:String)=text.dropLast(suffix(text).length)
    fun valid(text:String):Boolean=token(text).let{it.length in 1..48&&word.matches(it)}
}
