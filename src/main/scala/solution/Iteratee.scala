
package solution

import iteratee.IterateeContract._
import iteratee.LazyContext

object Iteratee {

  
  def counter[E]: Iteratee[E, Int] = {
    def step(count: Int): Input[E] => Iteratee[E, Int] = {
      case El(e) => Cont(step(count + 1))
      case EOF => Done(count, EOF)
      case Empty => Cont(step(count))
    }
    Cont(step(0))
  }
  
  def head[E]: Iteratee[E, Option[E]] = {
    def step: Input[E] => Iteratee[E, Option[E]] = {
      case El(e) => Done(Option(e), Empty)
      case EOF => Done(None, EOF)
      case Empty => Cont(step)
    }
    Cont(step)
  }

  
  def dropN[E](count: Int): Iteratee[E, Unit] = {
    def step(counter: Int): Input[E] => Iteratee[E, Unit] = {
      case El(e) if counter < count => Cont(step(counter + 1))
      case El(e) => Done((), El(e))
      case EOF => Done((), EOF)
      case Empty => Cont(step(counter))
    }
    Cont(step(0))
  } 

  def sum: Iteratee[Int, Int] = {
    def step(counter: Int): Input[Int] => Iteratee[Int, Int] = {
      case El(e)  => 
        Cont(step(counter + e))
      case EOF => 
        Done(counter, EOF)
      case Empty => 
        Cont(step(counter))
    }
    Cont(step(0))
  }  

  
  def getElementAt[E](count: Int): Iteratee[E, Option[E]] = for {
    _ <- dropN(count)
    e <- head
  } yield e


  trait SolutionIteratee[E, +A] extends Iteratee[E, A]{

    def run: A = fold {
      case Step.Done(a, _) => a
      case Step.Cont(k) => k(EOF).fold {
        case Step.Done(a, _) => a
        case Step.Cont(_) => sys.error("diverging iteratee after Input.EOF")
      }
    }

    def flatMap[B](f: A => Iteratee[E, B]): Iteratee[E, B] = this match {
      case Done(a, e) => f(a) match {
        case Done(a, _) => Done(a, e)
        case Cont(k) => k(e)
      }
      case Cont(k) => Cont(e => k(e) flatMap f)
    }

    def map[B](f: A => B): Iteratee[E, B] = this match {
      case Done(a, e) => Done(f(a), e)
      case Cont(k) => Cont(e => k(e) map f)
    }
  }

  case class Done[E, +A](state: A, remaining: Input[E]) extends SolutionIteratee[E, A] {
    def fold[B](folder: Step[E, A] => B): B = folder(Step.Done(state, remaining))
  }

  case class Cont[E, +A](k: Input[E] => Iteratee[E, A]) extends SolutionIteratee[E, A] {
    def fold[B](folder: Step[E, A] => B): B = folder(Step.Cont(k))    
  }
  
  
  def collect[E]: Iteratee[E, List[E]] = {
    def step(xs: List[E]): Input[E] => Iteratee[E, List[E]] = {
      case El(e) => Cont(step(xs :+ e))
      case Empty => Cont(step(xs))
      case EOF => Done(xs, EOF)
    }
    Cont(step(Nil))
  }
  
  def ignore[E]: Iteratee[E, Unit] = {
    Cont {
      case El(e) => Done((), Empty)
      case Empty => ignore
      case EOF => Done((), EOF)
    }
  }
  
  def lineIteratee: Iteratee[Char, String] = for {
    chars <- Enumeratee.takeWhile[Char](_ != '\n').transform(collect)
    _ <- Enumeratee.take(1).transform(ignore[Char]) 
  } yield chars.mkString

  
  
  object Enumerator {
    def apply[E](e: E*) = new Enumerator[E] {
      def apply[A](i: Iteratee[E, A]): LazyContext[Iteratee[E, A]] = {
        def step(xs: Seq[E])(i: Iteratee[E, A]): Iteratee[E, A] = (xs, i) match {
          case (Nil, Cont(k)) => k(EOF)
          case (x +: xs, Cont(k)) => step(xs)(k(El(x)))
          case (_, it@Done(_, _)) => i
        }        
        LazyContext(step(e)(i))
      }
    }
    
    import java.io._
    def fromStream(inputStream: InputStream): Enumerator[Byte] = new Enumerator[Byte]{
      def readChunk = inputStream.read match {
        case -1 => None
        case byte => Some(byte.toByte)
      }  
      def apply[A](i: Iteratee[Byte, A]): LazyContext[Iteratee[Byte, A]] = {(readChunk, i) match {
        case (None, Cont(k)) => LazyContext(k(EOF))
        case (Some(bytes), Cont(k)) => 
          apply(k(El(bytes)))
        case (_, i@Done(_, _)) => LazyContext(i)
      } } map (i => {inputStream.close(); i})
    }

  }
  
  trait SolutionEnumeratee[From, To] extends Enumeratee[From, To] {

    def continue[A](k: Input[To] => Iteratee[To, A]): Iteratee[From, Iteratee[To, A]] 

    def applyOn[A](i: Iteratee[To, A]): Iteratee[From, Iteratee[To, A]] = {
      i.fold {
        case Step.Cont(k) => continue(k)
        case _ => Done(i, Empty)
      }
    } 
    
    def transform[A](i: Iteratee[To, A]): Iteratee[From, A] = applyOn(i).flatMap { inner =>
      inner.fold {
        case Step.Done(a, _) => Done(a, Empty)
        case Step.Cont(k) => k(EOF).fold {
          case Step.Done(a, _) => Done(a, Empty)
          case Step.Cont(_) => sys.error("diverging iteratee after Input.EOF")          
        }
      }
    }
  }

  object Enumeratee {
    def map[From] = new {
      def apply[To](f: From => To): Enumeratee[From, To] = new SolutionEnumeratee[From, To] {

        def step[A](k: Input[To] => Iteratee[To, A]): Input[From] => Iteratee[From, Iteratee[To, A]] = {
          case in@(El(_) | Empty) => 
            val input: Input[To] = in.map(f)
            val inner = k(input)
            new SolutionEnumeratee[From, To] {
              def continue[A](k: Input[To] => Iteratee[To, A]): Iteratee[From, Iteratee[To, A]] = Cont(step(k))   
            }.applyOn(inner)
          case _ => 
            Done(Cont(k), EOF)
        }
        def continue[A](k: Input[To] => Iteratee[To, A]): Iteratee[From, Iteratee[To, A]] = Cont(step(k))
      }
    }
    
    def takeWhile[E](f: E => Boolean): Enumeratee[E, E] = new SolutionEnumeratee[E, E] {
      def step[A](k: Input[E] => Iteratee[E, A]): Input[E] => Iteratee[E, Iteratee[E, A]] = {
        case in@El(e) if f(e) => 
          new SolutionEnumeratee[E, E] {
            def continue[A](k: Input[E] => Iteratee[E, A]): Iteratee[E, Iteratee[E, A]] = Cont(step(k))   
          }.applyOn(k(in))
        case in@Empty => 
          new SolutionEnumeratee[E, E] {
            def continue[A](k: Input[E] => Iteratee[E, A]): Iteratee[E, Iteratee[E, A]] = Cont(step(k))   
          }.applyOn(k(in))  
        case _ => 
          Done(Cont(k), EOF)
      }
      def continue[A](k: Input[E] => Iteratee[E, A]): Iteratee[E, Iteratee[E, A]] = Cont(step(k))
    }
    
    def take[E](count: Int): Enumeratee[E, E] = new SolutionEnumeratee[E, E] {
      def step[A](remaining: Int)(k: Input[E] => Iteratee[E, A]): Input[E] => Iteratee[E, Iteratee[E, A]] = {
        case in@El(_) if remaining == 1 => 
          Done(k(in), Empty)
        case in@(El(_) | Empty) if remaining > 1 => 
          new SolutionEnumeratee[E, E] {
            def continue[A](k: Input[E] => Iteratee[E, A]): Iteratee[E, Iteratee[E, A]] = Cont(step(remaining - 1)(k))   
          }.applyOn(k(in))  
        case EOF => 
          Done(Cont(k), EOF)
          
        case in => Done(Cont(k), in)  
      }
      def continue[A](k: Input[E] => Iteratee[E, A]): Iteratee[E, Iteratee[E, A]] = Cont(step(count)(k))      
    }
    
    def grouped[From] = new {
      def apply[To](folder: Iteratee[From, To]): Enumeratee[From, To] = new SolutionEnumeratee[From, To] {
        def step[A](folder: Iteratee[From, To])(k: Input[To] => Iteratee[To, A]): Input[From] => Iteratee[From, Iteratee[To, A]] = {
          case in@(El(_) | Empty) => 
            val i = new Enumerator[From] {
              def apply[A](i: Iteratee[From, A]): LazyContext[Iteratee[From, A]] = {
                LazyContext {
                  i.fold {
                  	case Step.Cont(k) => k(in)
                  	case _ => i
                  }
                }
              }
            }
            i.apply(folder).run.fold {
              case Step.Cont(kF) => Cont(step(Cont(kF))(k))
              case Step.Done(a, left) => new SolutionEnumeratee[From, To] {
                def continue[A](k: Input[To] => Iteratee[To, A]): Iteratee[From, Iteratee[To, A]] = left match {
                  case in@El(_) => step(folder)(k)(left)
                  case _ => Cont(step(folder)(k))    
                }
              }.applyOn(k(El(a)))
            }
            
          case EOF => 
          
            folder.map {
              case to => Done(k(El(to)), EOF).run 
            }
        }
        def continue[A](k: Input[To] => Iteratee[To, A]): Iteratee[From, Iteratee[To, A]] = Cont(step(folder)(k))        
      }
    }  
  }  
}
