package dev.lip.stickers

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Strict bounded INTEGER-only JSON profile for the canonical sticker manifest. */
internal object StickerManifestJson {
    const val MAX_BYTES=65_536
    fun parse(bytes:ByteArray,live:()->Boolean):Map<String,Any?> {
        if(bytes.size>MAX_BYTES)throw StickerPackException(StickerPackException.Reason.LIMIT)
        val text=try{Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()}
            catch(_:java.nio.charset.CharacterCodingException){throw StickerPackException(StickerPackException.Reason.JSON)}
        val value=Parser(text,live).document()
        @Suppress("UNCHECKED_CAST")
        return value as? Map<String,Any?>?:throw StickerPackException(StickerPackException.Reason.JSON)
    }
    private class Parser(val text:String,val live:()->Boolean){
        var at=0;var nodes=0
        fun refuse():Nothing=throw StickerPackException(StickerPackException.Reason.JSON)
        fun checkLive(){if(!live())throw StickerPackException(StickerPackException.Reason.CANCELED)}
        fun whitespace(){while(at<text.length&&text[at] in " \r\n\t")at++}
        fun take(c:Char):Boolean{if(at<text.length&&text[at]==c){at++;return true};return false}
        fun expect(c:Char){if(!take(c))refuse()}
        fun document():Any?{checkLive();whitespace();val v=value(0);whitespace();if(at!=text.length)refuse();checkLive();return v}
        fun value(depth:Int):Any?{
            checkLive();if(depth>12||++nodes>8192)throw StickerPackException(StickerPackException.Reason.LIMIT)
            whitespace();if(at>=text.length)refuse()
            return when(text[at]){
                '{'->{at++;val out=LinkedHashMap<String,Any?>();whitespace();if(take('}'))out else{
                    while(true){whitespace();if(at>=text.length||text[at]!='"')refuse();val key=string();if(out.containsKey(key))refuse();whitespace();expect(':');out[key]=value(depth+1);whitespace();if(take('}'))break;expect(',')};out
                }}
                '['->{at++;val out=ArrayList<Any?>();whitespace();if(take(']'))out else{
                    while(true){out.add(value(depth+1));whitespace();if(take(']'))break;expect(',')};out
                }}
                '"'->string()
                't'->{literal("true");true}
                'f'->{literal("false");false}
                'n'->{literal("null");null}
                '-',in '0'..'9'->number()
                else->refuse()
            }
        }
        fun literal(value:String){if(!text.startsWith(value,at))refuse();at+=value.length}
        fun number():Long{
            val start=at;take('-');if(at>=text.length)refuse()
            if(take('0')){if(at<text.length&&text[at] in '0'..'9')refuse()}
            else{if(text[at] !in '1'..'9')refuse();while(at<text.length&&text[at] in '0'..'9'){if(at%64==0)checkLive();at++}}
            if(at<text.length&&text[at] in ".eE")refuse()
            return text.substring(start,at).toLongOrNull()?:throw StickerPackException(StickerPackException.Reason.LIMIT)
        }
        fun hex():Char{
            if(at>text.length-4)refuse();var value=0
            repeat(4){val c=text[at++];val digit=when(c){in '0'..'9'->c-'0';in 'a'..'f'->c-'a'+10;in 'A'..'F'->c-'A'+10;else->refuse()};value=(value shl 4) or digit}
            return value.toChar()
        }
        fun string():String{
            expect('"');val out=StringBuilder()
            while(at<text.length){
                if(at%64==0)checkLive();val c=text[at++]
                if(c=='"')return out.toString()
                if(c<' ')refuse()
                if(c=='\\'){
                    if(at>=text.length)refuse()
                    when(val escape=text[at++]){
                        '"','\\','/'->out.append(escape)
                        'b'->out.append('\b');'f'->out.append('\u000c');'n'->out.append('\n');'r'->out.append('\r');'t'->out.append('\t')
                        'u'->{val first=hex();if(first.isHighSurrogate()){
                            expect('\\');expect('u');val second=hex();if(!second.isLowSurrogate())refuse();out.append(first).append(second)
                        }else{if(first.isLowSurrogate())refuse();out.append(first)}}
                        else->refuse()
                    }
                }else if(c.isHighSurrogate()){
                    if(at>=text.length||!text[at].isLowSurrogate())refuse();out.append(c).append(text[at++])
                }else{if(c.isLowSurrogate())refuse();out.append(c)}
            }
            refuse()
        }
    }
}
