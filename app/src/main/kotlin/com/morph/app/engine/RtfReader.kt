package com.morph.app.engine

import java.nio.charset.Charset

/** Decodes RTF groups, escaped ANSI bytes, signed Unicode escapes, and paragraph controls. */
object RtfReader {
    private data class State(var skip:Boolean=false,var fallback:Int=1,var charset:Charset=Charset.forName("windows-1252"))
    private val destinations=setOf("fonttbl","colortbl","stylesheet","info","pict","object","header","footer","listtable","listoverridetable","generator","fldinst")
    fun text(source:String):String {
        val groups=ArrayDeque<State>();var state=State();val out=StringBuilder()
        var i=0;var fallback=0
        fun emit(c:Char){if(fallback>0){fallback--;return};if(!state.skip)out.append(c)}
        while(i<source.length){
            when(val c=source[i++]){
                '{'->{groups.addLast(state);state=state.copy()}
                '}'->{if(groups.isNotEmpty())state=groups.removeLast()}
                '\r','\n'->Unit
                '\\'->{
                    if(i>=source.length)break
                    val control=source[i++]
                    when {
                        control=='*'->state.skip=true
                        control=='\''->{if(i+2<=source.length){source.substring(i,i+2).toIntOrNull(16)?.let {emit(byteArrayOf(it.toByte()).toString(state.charset).first())};i+=2}}
                        !control.isLetter()->when(control){'\\','{','}'->emit(control);'~'->emit('\u00a0');'_'->emit('\u2011');else->Unit}
                        else->{
                            val start=i-1;while(i<source.length && source[i].isLetter())i++
                            val word=source.substring(start,i);val numeric=i
                            if(i<source.length && source[i]=='-')i++
                            while(i<source.length && source[i].isDigit())i++
                            val n=source.substring(numeric,i).toIntOrNull()
                            if(i<source.length && source[i]==' ')i++
                            when(word){
                                in destinations->state.skip=true
                                "bin"->i=(i+(n ?: 0).coerceAtLeast(0)).coerceAtMost(source.length)
                                "ansicpg"->n?.let {runCatching {Charset.forName("windows-$it")}.getOrNull()?.let {cs->state.charset=cs}}
                                "uc"->state.fallback=(n ?: 1).coerceIn(0,32)
                                "u"->if(n!=null){if(!state.skip)out.append(n.toChar());fallback=state.fallback}
                                "par","line"->if(!state.skip)out.append('\n')
                                "tab","cell"->if(!state.skip)out.append('\t')
                                "emdash"->emit('—');"endash"->emit('–');"bullet"->emit('•')
                            }
                        }
                    }
                }
                else->emit(c)
            }
        }
        return out.toString()
    }
}
